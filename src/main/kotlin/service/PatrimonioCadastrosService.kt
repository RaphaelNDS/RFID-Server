package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.LocalEntity
import org.example.model.ResponsavelEntity
import org.example.model.UnidadeEntity
import org.example.repository.LocalRepository
import org.example.repository.ResponsavelRepository
import org.example.repository.TagRepository
import org.example.repository.UnidadeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** Uma unidade com os locais dela e quantos bens (não baixados) cada um tem (tela Locais). */
data class UnidadeComLocais(val unidade: UnidadeEntity?, val locais: List<LocalComBens>)

data class LocalComBens(val local: LocalEntity, val bens: Long)

/** Um local para escolher (cadastro e edição da etiqueta, filtros): "Matriz › Sala 101". */
data class OpcaoLocal(val id: Long, val nome: String, val unidade: String?, val ativo: Boolean) {
    val descricao: String get() = if (unidade == null) nome else "$unidade › $nome"
}

/**
 * Cadastros da gestão de patrimônio (1.6.0): unidades, locais e responsáveis. Nomes sem diferenciar maiúsculas nem
 * espaços repetidos ("sala 101" e " Sala  101 " são o mesmo local, como no inventário). Nada é excluído quando já foi
 * usado: desativar tira das escolhas e mantém os bens e os inventários antigos. Tudo vai para a auditoria.
 */
@Service
class PatrimonioCadastrosService(
    private val unidades: UnidadeRepository,
    private val locais: LocalRepository,
    private val responsaveis: ResponsavelRepository,
    private val tags: TagRepository,
    private val relogio: Clock,
    private val auditoria: AuditoriaService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // ================= UNIDADES =================

    fun listarUnidades(): List<UnidadeEntity> = unidades.findAllByOrderByNomeAsc()

    @Transactional
    fun salvarUnidade(id: Long?, nomeInformado: String, ativa: Boolean = true): UnidadeEntity {
        val nome = nome(nomeInformado, "da unidade", 120)
        unidades.porNome(nome)?.takeIf { it.id != id }?.let { throw IllegalArgumentException("Já existe a unidade ${it.nome}") }
        val unidade = if (id == null) unidades.save(UnidadeEntity(nome = nome, criadaEm = LocalDateTime.now(relogio)))
        else unidades.findById(id).orElseThrow { NoSuchElementException("Unidade $id não encontrada") }.also {
            it.nome = nome
            it.ativa = ativa
            unidades.save(it)
        }
        log.info("Unidade {} {} (ativa: {})", unidade.nome, if (id == null) "cadastrada" else "alterada", unidade.ativa)
        auditoria?.registrar(AreaAuditoria.CATALOGO, if (id == null) "Unidade cadastrada" else "Unidade alterada", unidade.nome,
            if (unidade.ativa) null else "Desativada")
        return unidade
    }

    // ================= LOCAIS =================

    /** As unidades com os locais (os sem unidade por último), para a tela Locais. */
    fun locaisPorUnidade(): List<UnidadeComLocais> {
        val bens = tags.contarPorLocal().associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }
        val porUnidade = locais.findAllByOrderByNomeAsc().map { LocalComBens(it, bens[it.id] ?: 0) }.groupBy { it.local.unidadeId }
        return listarUnidades().map { UnidadeComLocais(it, porUnidade[it.id].orEmpty()) } +
            listOfNotNull(porUnidade[null]?.let { UnidadeComLocais(null, it) })
    }

    /** Os locais para escolher, "Unidade › Local", em ordem. [incluirInativos] para a edição de um bem já num inativo. */
    fun opcoesDeLocal(incluirInativos: Boolean = false): List<OpcaoLocal> {
        val nomesUnidade = listarUnidades().associate { it.id to it.nome }
        return locais.findAllByOrderByNomeAsc()
            .filter { incluirInativos || it.ativo }
            .map { OpcaoLocal(it.id, it.nome, it.unidadeId?.let(nomesUnidade::get), it.ativo) }
            .sortedWith(compareBy<OpcaoLocal, String?>(nullsLast()) { it.unidade?.lowercase() }.thenBy { it.nome.lowercase() })
    }

    @Transactional
    fun salvarLocal(id: Long?, nomeInformado: String, unidadeId: Long?, ativo: Boolean = true): LocalEntity {
        val nome = limparNomeLocal(nome(nomeInformado, "do local", 120))
        unidadeId?.let { unidades.findById(it).orElseThrow { IllegalArgumentException("Unidade não encontrada") } }
        locais.findFirstByNomeIgnoreCaseOrderByIdAsc(nome).orElse(null)?.takeIf { it.id != id }?.let {
            throw IllegalArgumentException("Já existe o local ${it.nome}: o nome do local é único (o coletor escolhe o local pelo nome)")
        }
        val local = if (id == null) locais.save(LocalEntity(nome = nome, criadoEm = LocalDateTime.now(relogio), unidadeId = unidadeId))
        else locais.findById(id).orElseThrow { NoSuchElementException("Local $id não encontrado") }.also {
            it.nome = nome
            it.unidadeId = unidadeId
            it.ativo = ativo
            locais.save(it)
        }
        log.info("Local {} {} (unidade {}, ativo: {})", local.nome, if (id == null) "cadastrado" else "alterado", unidadeId ?: "—", local.ativo)
        auditoria?.registrar(AreaAuditoria.CATALOGO, if (id == null) "Local cadastrado" else "Local alterado", local.nome,
            listOfNotNull(unidadeId?.let { u -> unidades.findById(u).map { "Unidade ${it.nome}" }.orElse(null) }, if (local.ativo) null else "Desativado")
                .joinToString().ifEmpty { null })
        return local
    }

    // ================= RESPONSÁVEIS =================

    fun listarResponsaveis(): List<ResponsavelEntity> = responsaveis.findAllByOrderByNomeAsc()

    fun responsaveisAtivos(): List<ResponsavelEntity> = listarResponsaveis().filter { it.ativo }

    fun bensPorResponsavel(): Map<Long, Long> = tags.contarPorResponsavel().associate { (it[0] as Number).toLong() to (it[1] as Number).toLong() }

    fun responsavel(id: Long): ResponsavelEntity = responsaveis.findById(id).orElseThrow { NoSuchElementException("Responsável $id não encontrado") }

    @Transactional
    fun salvarResponsavel(id: Long?, nomeInformado: String, setor: String?, matricula: String?, email: String?, ativo: Boolean = true): ResponsavelEntity {
        val nome = nome(nomeInformado, "do responsável", 160)
        val mat = opcional(matricula, "A matrícula", 60)
        val mail = opcional(email, "O email", 160)?.also { require(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it)) { "Email inválido: $it" } }
        mat?.let { m -> responsaveis.porMatricula(m)?.takeIf { it.id != id }?.let { throw IllegalArgumentException("A matrícula $m já é de ${it.nome}") } }
        val r = if (id == null) ResponsavelEntity(nome = nome, criadoEm = LocalDateTime.now(relogio)) else responsavel(id)
        r.nome = nome
        r.setor = opcional(setor, "O setor", 120)
        r.matricula = mat
        r.email = mail
        r.ativo = ativo
        val salvo = responsaveis.save(r)
        log.info("Responsável {} {} (ativo: {})", salvo.descricao, if (id == null) "cadastrado" else "alterado", salvo.ativo)
        auditoria?.registrar(AreaAuditoria.CATALOGO, if (id == null) "Responsável cadastrado" else "Responsável alterado", salvo.descricao,
            if (salvo.ativo) null else "Desativado")
        return salvo
    }

    // ================= IMPORTAÇÃO (procurar ou criar pelo nome) =================

    /** A unidade pelo nome; com [criar], cadastra se não existir. null = não existe e não criou. */
    @Transactional
    fun unidadePorNome(nome: String, criar: Boolean): UnidadeEntity? {
        val limpo = limparNomeLocal(nome)
        if (limpo.isEmpty()) return null
        return unidades.porNome(limpo) ?: if (criar) salvarUnidade(null, limpo) else null
    }

    /**
     * O local pelo nome; com [criar], cadastra na [unidade] se não existir. Local que já existe numa unidade diferente da
     * informada é recusado (o nome é único no sistema todo, e mudar de unidade é pela tela Locais). Local sem unidade
     * ganha a [unidade] informada só com [atualizar] (a simulação da importação não grava nada).
     */
    @Transactional
    fun localPorNome(nome: String, unidade: UnidadeEntity?, criar: Boolean, atualizar: Boolean = criar): LocalEntity? {
        val limpo = limparNomeLocal(nome)
        if (limpo.isEmpty()) return null
        val existente = locais.findFirstByNomeIgnoreCaseOrderByIdAsc(limpo).orElse(null)
        if (existente != null) {
            if (unidade != null && existente.unidadeId != null && existente.unidadeId != unidade.id) {
                val atual = unidades.findById(existente.unidadeId!!).map { it.nome }.orElse("?")
                throw IllegalArgumentException("O local ${existente.nome} é da unidade $atual, e não de ${unidade.nome}")
            }
            if (atualizar && unidade != null && existente.unidadeId == null) {
                existente.unidadeId = unidade.id   // local criado pelo coletor ganha a unidade da planilha
                locais.save(existente)
            }
            return existente
        }
        return if (criar) salvarLocal(null, limpo, unidade?.id) else null
    }

    /** O responsável pela matrícula (se informada) ou pelo nome; com [criar], cadastra se não existir. */
    @Transactional
    fun responsavelPor(nome: String?, matricula: String?, criar: Boolean): ResponsavelEntity? {
        val mat = matricula?.trim()?.takeIf { it.isNotEmpty() }
        val n = nome?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
        mat?.let { responsaveis.porMatricula(it) }?.let { return it }
        n?.let { responsaveis.porNome(it).firstOrNull() }?.let { return it }
        if (!criar || n == null) return null
        return salvarResponsavel(null, n, null, mat, null)
    }

    private fun nome(valor: String, de: String, maximo: Int): String {
        val limpo = valor.trim().replace(Regex("\\s+"), " ")
        require(limpo.isNotEmpty()) { "Informe o nome $de" }
        require(limpo.length <= maximo) { "O nome $de pode ter no máximo $maximo caracteres" }
        return limpo
    }

    private fun opcional(valor: String?, campo: String, maximo: Int): String? {
        val limpo = valor?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(limpo.length <= maximo) { "$campo pode ter no máximo $maximo caracteres" }
        return limpo
    }
}
