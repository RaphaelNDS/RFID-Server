package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.CampoTipoEntity
import org.example.model.FormatoCampo
import org.example.model.TagEntity
import org.example.model.ValorCampoEntity
import org.example.repository.CampoTipoRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.repository.ValorCampoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Um campo na tela da etiqueta, com o valor atual do equipamento. */
data class CampoComValor(val campo: CampoTipoEntity, val valor: String?) {
    /** O valor como aparece para as pessoas (data em dd/MM/aaaa). */
    val exibido: String? get() = valor?.let { CampoPersonalizadoService.exibir(campo, it) }
}

/** Um tipo e os campos dele, para a tela de campos. */
data class TipoComCampos(val tipoId: Long, val tipo: String, val campos: List<CampoTipoEntity>)

/**
 * Campos personalizados por tipo de equipamento (1.22.0). Cada tipo tem os seus campos (texto, número, data, lista de
 * opções ou sim/não); o valor de cada equipamento aparece e é editado na edição da etiqueta, entra na busca da tela de
 * etiquetas e vira coluna na exportação. Cada mudança de valor vai para o histórico da etiqueta.
 *
 * Desativar um campo esconde o campo da edição e da exportação, sem apagar os valores (reativar traz de volta). O formato
 * só muda enquanto nenhum equipamento tem valor no campo (senão os valores antigos poderiam ficar inválidos).
 */
@Service
class CampoPersonalizadoService(
    private val campos: CampoTipoRepository,
    private val valores: ValorCampoRepository,
    private val tipos: TipoEquipamentoRepository,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /* ================= CAMPOS (cadastro) ================= */

    @Transactional(readOnly = true)
    fun tiposComCampos(): List<TipoComCampos> {
        val porTipo = campos.findAll().groupBy { it.tipoEquipamentoId }
        return tipos.findAll().sortedBy { it.nome.lowercase() }.map { t ->
            TipoComCampos(t.id, t.nome, porTipo[t.id].orEmpty().sortedWith(compareBy({ !it.ativo }, { it.ordem }, { it.id })))
        }
    }

    /** Os campos ativos do tipo, na ordem da tela. */
    @Transactional(readOnly = true)
    fun camposAtivos(tipoId: Long?): List<CampoTipoEntity> =
        if (tipoId == null) emptyList() else campos.findAllByTipoEquipamentoIdOrderByOrdemAscIdAsc(tipoId).filter { it.ativo }

    @Transactional
    fun salvarCampo(id: Long?, tipoId: Long?, nome: String?, formato: FormatoCampo?, opcoes: String?, obrigatorio: Boolean, ordem: Int?): CampoTipoEntity {
        val n = nome?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Informe o nome do campo")
        require(n.length <= 80) { "O nome do campo pode ter no máximo 80 caracteres" }
        val f = formato ?: throw IllegalArgumentException("Escolha o formato do campo")
        val atual = id?.let { campos.findById(it).orElseThrow { IllegalArgumentException("Campo $id não encontrado") } }
        val tipo = atual?.tipoEquipamentoId ?: tipoId ?: throw IllegalArgumentException("Escolha o tipo de equipamento")
        val nomeTipo = tipos.findById(tipo).orElseThrow { IllegalArgumentException("Tipo de equipamento $tipo não encontrado") }.nome
        campos.findAllByTipoEquipamentoIdOrderByOrdemAscIdAsc(tipo).firstOrNull { it.id != atual?.id && it.nome.equals(n, ignoreCase = true) }?.let {
            throw IllegalArgumentException("O tipo $nomeTipo já tem o campo ${it.nome}")
        }
        val listaOpcoes = opcoes?.split(';', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinctBy { it.lowercase() }.orEmpty()
        if (f == FormatoCampo.LISTA) {
            require(listaOpcoes.size >= 2) { "A lista precisa de pelo menos 2 opções, separadas por ponto e vírgula" }
            require(listaOpcoes.joinToString(";").length <= 1000) { "As opções passam de 1000 caracteres" }
        }
        if (atual != null && atual.formato != f) {
            require(!valores.existsByCampoId(atual.id)) { "O campo ${atual.nome} já tem valores: o formato não muda (crie outro campo)" }
        }
        if (atual != null && atual.formato == FormatoCampo.LISTA && f == FormatoCampo.LISTA) {
            // Tirar uma opção que algum equipamento usa deixaria o valor órfão
            val removidas = atual.listaOpcoes.filter { o -> listaOpcoes.none { it.equals(o, ignoreCase = true) } }
            if (removidas.isNotEmpty()) {
                val emUso = valores.findAllByCampoId(atual.id).filter { removidas.any { r -> r.equals(it.valor, ignoreCase = true) } }
                require(emUso.isEmpty()) { "A opção ${emUso.first().valor} está em uso em ${emUso.size} equipamento(s): não sai da lista" }
            }
        }
        val c = atual ?: CampoTipoEntity(tipoEquipamentoId = tipo, nome = n, formato = f)
        c.nome = n
        c.formato = f
        c.opcoes = if (f == FormatoCampo.LISTA) listaOpcoes.joinToString(";") else null
        c.obrigatorio = obrigatorio
        c.ordem = ordem ?: c.ordem
        val salvo = campos.save(c)
        val usuario = historico.autor().first
        log.info("Campo personalizado '{}' ({}{}) do tipo {} {} por {}", salvo.nome, f.rotulo, if (obrigatorio) ", obrigatório" else "",
            nomeTipo, if (atual == null) "criado" else "alterado", usuario)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, if (atual == null) "Campo personalizado criado" else "Campo personalizado alterado",
            nomeTipo, "${salvo.nome} (${f.rotulo})")
        return salvo
    }

    @Transactional
    fun ativarCampo(id: Long, ativo: Boolean): CampoTipoEntity {
        val c = campos.findById(id).orElseThrow { IllegalArgumentException("Campo $id não encontrado") }
        c.ativo = ativo
        campos.save(c)
        log.info("Campo personalizado '{}' {} por {}", c.nome, if (ativo) "reativado" else "desativado", historico.autor().first)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, if (ativo) "Campo personalizado reativado" else "Campo personalizado desativado", c.nome)
        return c
    }

    /* ================= VALORES (por equipamento) ================= */

    /** Os campos ativos do tipo do equipamento, com o valor de cada um. */
    @Transactional(readOnly = true)
    fun doBem(tag: TagEntity): List<CampoComValor> {
        val atuais = valores.findAllByTagId(tag.id).associate { it.campoId to it.valor }
        return camposAtivos(tag.tipoEquipamentoId).map { CampoComValor(it, atuais[it.id]) }
    }

    /**
     * Confere o que veio da tela ([entradas]: id do campo → texto digitado) contra os campos ativos do [tipoId]:
     * formato e obrigatório. Devolve id → valor normalizado (null = apagar). Campos de outro tipo são ignorados.
     */
    fun validar(tipoId: Long?, entradas: Map<Long, String?>, exigirObrigatorios: Boolean = true): Map<Long, String?> {
        val erros = mutableListOf<String>()
        val normalizados = camposAtivos(tipoId).associate { c ->
            val bruto = entradas[c.id]?.trim()?.takeIf { it.isNotEmpty() }
            val valor = try {
                bruto?.let { normalizar(c, it) }
            } catch (e: IllegalArgumentException) {
                erros += e.message!!
                null
            }
            if (exigirObrigatorios && valor == null && bruto == null && c.obrigatorio) erros += "Preencha o campo ${c.nome}"
            c.id to valor
        }
        require(erros.isEmpty()) { erros.joinToString("; ") }
        return normalizados
    }

    /** Grava os valores já validados do equipamento; cada mudança vai para o histórico. Devolve os nomes que mudaram. */
    @Transactional
    fun gravar(tag: TagEntity, normalizados: Map<Long, String?>): List<String> {
        if (normalizados.isEmpty()) return emptyList()
        val porId = campos.findAllById(normalizados.keys).associateBy { it.id }
        val atuais = valores.findAllByTagId(tag.id).associateBy { it.campoId }
        val mudaram = mutableListOf<String>()
        normalizados.forEach { (campoId, novo) ->
            val campo = porId[campoId] ?: return@forEach
            val antigo = atuais[campoId]
            if (antigo?.valor == novo) return@forEach
            when {
                novo == null -> valores.delete(antigo!!)
                antigo == null -> valores.save(ValorCampoEntity(tagId = tag.id, campoId = campoId, valor = novo))
                else -> { antigo.valor = novo; valores.save(antigo) }
            }
            historico.registrarCampo(tag, campo.nome, antigo?.valor?.let { exibir(campo, it) }, novo?.let { exibir(campo, it) })
            mudaram += campo.nome
        }
        if (mudaram.isNotEmpty()) log.info("Campos personalizados da etiqueta {} alterados por {}: {}", tag.tagReal, historico.autor().first, mudaram.joinToString())
        return mudaram
    }

    /* ================= EXPORTAÇÃO ================= */

    /** Os nomes dos campos ativos (sem repetir o mesmo nome em tipos diferentes), na ordem: viram colunas da planilha. */
    @Transactional(readOnly = true)
    fun colunas(): List<String> =
        campos.findAllByAtivoTrueOrderByTipoEquipamentoIdAscOrdemAscIdAsc().map { it.nome }.distinctBy { it.lowercase() }

    /** Para cada etiqueta, nome do campo (minúsculo) → valor exibido. Em lotes, para qualquer quantidade. */
    @Transactional(readOnly = true)
    fun valoresPorNome(tagIds: Collection<Long>): Map<Long, Map<String, String>> {
        if (tagIds.isEmpty()) return emptyMap()
        val ativos = campos.findAllByAtivoTrueOrderByTipoEquipamentoIdAscOrdemAscIdAsc().associateBy { it.id }
        return tagIds.chunked(1000).flatMap { valores.findAllByTagIdIn(it) }
            .filter { it.campoId in ativos }
            .groupBy { it.tagId }
            .mapValues { (_, l) -> l.associate { v -> ativos.getValue(v.campoId).let { c -> c.nome.lowercase() to exibir(c, v.valor) } } }
    }

    companion object {
        private val DATA_BR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val NUMERO = Regex("^-?\\d{1,15}([.,]\\d{1,6})?$")

        /** Confere o valor digitado e devolve como fica gravado. */
        fun normalizar(c: CampoTipoEntity, bruto: String): String = when (c.formato) {
            FormatoCampo.TEXTO -> bruto.also { require(it.length <= 500) { "${c.nome}: no máximo 500 caracteres" } }
            FormatoCampo.NUMERO -> bruto.replace(" ", "").also { require(NUMERO.matches(it)) { "${c.nome}: informe um número (ex.: 12 ou 12,5)" } }.replace('.', ',')
            FormatoCampo.DATA -> lerData(bruto)?.toString() ?: throw IllegalArgumentException("${c.nome}: data inválida (use dd/mm/aaaa)")
            FormatoCampo.LISTA -> c.listaOpcoes.firstOrNull { it.equals(bruto, ignoreCase = true) }
                ?: throw IllegalArgumentException("${c.nome}: escolha uma das opções (${c.listaOpcoes.joinToString(", ")})")
            FormatoCampo.SIM_NAO -> when (bruto.lowercase()) {
                "sim", "s", "true" -> "Sim"
                "não", "nao", "n", "false" -> "Não"
                else -> throw IllegalArgumentException("${c.nome}: responda Sim ou Não")
            }
        }

        /** Como o valor gravado aparece (a data em dd/MM/aaaa). */
        fun exibir(c: CampoTipoEntity, valor: String): String =
            if (c.formato == FormatoCampo.DATA) runCatching { LocalDate.parse(valor).format(DATA_BR) }.getOrDefault(valor) else valor

        private fun lerData(texto: String): LocalDate? =
            runCatching { LocalDate.parse(texto) }.getOrNull() ?: runCatching { LocalDate.parse(texto, DATA_BR) }.getOrNull()
    }
}
