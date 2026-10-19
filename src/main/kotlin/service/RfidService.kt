package org.example.service

import org.springframework.transaction.annotation.Transactional
import org.example.model.TagEntity
import org.example.model.TagNaoCadastradaEntity
import org.example.repository.*
import org.example.request.CadastroRequest
import org.example.request.TagNaoCadastradaView
import org.example.response.TagResponse
import org.springframework.stereotype.Service
import java.security.MessageDigest
import org.example.response.TagViewAdmin
import java.time.LocalDateTime
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder

@Service
class RfidService(
    private val repo: TagRepository,
    private val naoRepo: TagNaoCadastradaRepository,
    private val tipoRepo: TipoEquipamentoRepository,
    private val marcaRepo: MarcaRepository,
    private val modeloRepo: ModeloRepository,
    private val historico: HistoricoEtiquetaService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /* ================= CADASTRO NORMAL ================= */

    @Transactional
    fun cadastrarTag(pedido: CadastroRequest) {

        // Web e coletor: espaços colados no EPC gerariam outro hash e a leitura nunca bateria com o cadastro
        val req = normalizar(pedido)

        require(req.tag.isNotBlank()) { "Informe a TAG RFID" }

        if (repo.existsByTagReal(req.tag)) {
            log.warn("Cadastro recusado: a tag {} já está cadastrada (tentativa de {})", req.tag, usuarioAtual())
            throw IllegalArgumentException("Esta etiqueta já está cadastrada")
        }

        require(req.codigoInterno.isNotBlank()) { "Informe o código interno" }
        validarCatalogo(req)
        recusarPatrimonioRepetido(req.patrimonio, idAtual = null)

        val hash = hashTag(req.tag)

        val entity = TagEntity(
            tagHash = hash,
            tagReal = req.tag,
            codigoInterno = req.codigoInterno,
            patrimonio = req.patrimonio,
            numeroSerie = req.numeroSerie,

            tipoEquipamentoId = req.tipoEquipamentoId,
            marcaId = req.marcaId,
            modeloId = req.modeloId
        )

        val salva = repo.save(entity)
        historico.registrarCadastro(salva)

        naoRepo.deleteByTag(req.tag)

        log.info(
            "Etiqueta cadastrada: {} (patrimônio: {}) por {}",
            req.tag, req.patrimonio, usuarioAtual()
        )
    }

    fun buscarPorTag(tagReal: String): TagResponse? {

        val hash = hashTag(tagReal)
        val tag = repo.findByTagHash(hash) ?: return null

        val tipoNome = tag.tipoEquipamentoId
            ?.let { tipoRepo.findById(it).orElse(null) }
            ?.nome

        val marcaNome = tag.marcaId
            ?.let { marcaRepo.findById(it).orElse(null) }
            ?.nome

        val modeloNome = tag.modeloId
            ?.let { modeloRepo.findById(it).orElse(null) }
            ?.nome

        return TagResponse(
            codigoInterno = tag.codigoInterno,
            tipo = tipoNome,
            marca = marcaNome,
            modelo = modeloNome,
            patrimonio = tag.patrimonio,
            numeroSerie = tag.numeroSerie
        )
    }


    /**
     * Confere que tipo, marca e modelo existem e combinam entre si (a marca é do tipo escolhido,
     * o modelo é da marca escolhida). Todos são opcionais, mas a hierarquia precisa ser respeitada.
     */
    private fun validarCatalogo(req: CadastroRequest) {

        fun recusar(motivo: String): Nothing {
            log.warn(
                "Cadastro recusado: {} (tag {}, tipo {}, marca {}, modelo {}, por {})",
                motivo, req.tag, req.tipoEquipamentoId, req.marcaId, req.modeloId, usuarioAtual()
            )
            throw IllegalArgumentException(motivo)
        }

        val tipo = req.tipoEquipamentoId?.let { id ->
            tipoRepo.findById(id).orElse(null) ?: recusar("Tipo de equipamento não encontrado")
        }

        val marca = req.marcaId?.let { id ->
            marcaRepo.findById(id).orElse(null) ?: recusar("Marca não encontrada")
        }

        val modelo = req.modeloId?.let { id ->
            modeloRepo.findById(id).orElse(null) ?: recusar("Modelo não encontrado")
        }

        if (marca != null && tipo == null) recusar("Selecione o tipo de equipamento da marca ${marca.nome}")
        if (modelo != null && marca == null) recusar("Selecione a marca do modelo ${modelo.nome}")

        if (marca != null && tipo!!.id != marca.tipo.id) {
            recusar("A marca ${marca.nome} não pertence ao tipo de equipamento ${tipo.nome}")
        }

        if (modelo != null && marca!!.id != modelo.marca.id) {
            recusar("O modelo ${modelo.nome} não pertence à marca ${marca.nome}")
        }
    }

    // Carrega o catálogo uma vez só (antes eram 3 consultas por etiqueta listada)
    fun listarParaAdmin(): List<TagViewAdmin> {
        val tipos = tipoRepo.findAll().associate { it.id to it.nome }
        val marcas = marcaRepo.findAll().associate { it.id to it.nome }
        val modelos = modeloRepo.findAll().associate { it.id to it.nome }

        return repo.findAll().map { tag ->
            TagViewAdmin(
                id = tag.id,
                tag = tag.tagReal,
                codigoInterno = tag.codigoInterno,
                tipoNome = tag.tipoEquipamentoId?.let { tipos[it] },
                marcaNome = tag.marcaId?.let { marcas[it] },
                modeloNome = tag.modeloId?.let { modelos[it] },
                patrimonio = tag.patrimonio,
                numeroSerie = tag.numeroSerie
            )
        }
    }



    /* ================= EDIÇÃO E EXCLUSÃO ================= */

    fun buscarPorId(id: Long): TagEntity =
        repo.findById(id).orElseThrow { NoSuchElementException("Etiqueta $id não encontrada") }

    /**
     * Altera os dados da etiqueta (o EPC não muda: é a própria etiqueta). Mesmas regras de catálogo do cadastro.
     * Grava no histórico uma linha por campo que mudou, na mesma transação. Devolve os campos alterados.
     */
    @Transactional
    fun editarTag(id: Long, pedido: CadastroRequest): List<String> {
        val tag = buscarPorId(id)
        val req = normalizar(pedido)
        require(req.codigoInterno.isNotBlank()) { "Informe o código interno" }
        validarCatalogo(req.copy(tag = tag.tagReal))
        recusarPatrimonioRepetido(req.patrimonio, idAtual = tag.id)

        val antes = historico.retrato(tag)
        tag.codigoInterno = req.codigoInterno
        tag.patrimonio = req.patrimonio
        tag.numeroSerie = req.numeroSerie
        tag.tipoEquipamentoId = req.tipoEquipamentoId
        tag.marcaId = req.marcaId
        tag.modeloId = req.modeloId
        repo.save(tag)

        val mudaram = historico.registrarAlteracao(tag, antes)
        if (mudaram.isEmpty()) {
            log.info("Etiqueta {} salva sem mudanças por {}", tag.tagReal, usuarioAtual())
        } else {
            log.info("Etiqueta {} (patrimônio {}) alterada por {}: {}", tag.tagReal, tag.patrimonio, usuarioAtual(), mudaram.joinToString())
        }
        return mudaram
    }

    private fun normalizar(req: CadastroRequest) = req.copy(
        tag = req.tag.trim(),
        codigoInterno = req.codigoInterno.trim(),
        patrimonio = req.patrimonio.trim(),
        numeroSerie = req.numeroSerie.trim()
    )

    /**
     * Patrimônio identifica o equipamento: duas etiquetas com o mesmo número confundem o Localizar e os relatórios.
     * Vazio é permitido (etiqueta ainda sem patrimônio). [idAtual] é a própria etiqueta, na edição.
     */
    private fun recusarPatrimonioRepetido(patrimonio: String, idAtual: Long?) {
        if (patrimonio.isBlank()) return
        val outra = repo.findAllByPatrimonio(patrimonio).firstOrNull { it.id != idAtual } ?: return

        log.warn("Cadastro recusado: o patrimônio {} já está na etiqueta {} (tentativa de {})", patrimonio, outra.tagReal, usuarioAtual())
        throw IllegalArgumentException("O patrimônio $patrimonio já está em outra etiqueta (${outra.tagReal})")
    }

    /** Exclui a etiqueta. O histórico dela continua, com um retrato dos campos no momento da exclusão. */
    @Transactional
    fun excluirTag(id: Long): TagEntity {
        val tag = buscarPorId(id)
        val antes = historico.retrato(tag)

        repo.delete(tag)
        historico.registrarExclusao(tag.id, tag.tagReal, antes)

        log.warn("Etiqueta {} excluída por {} ({})", tag.tagReal, usuarioAtual(), antes.resumo())
        return tag
    }

    /* ================= NÃO CADASTRADAS ================= */

    fun salvarNaoCadastrada(tag: String) {

        require(tag.isNotBlank()) { "Informe a TAG RFID" }

        if (repo.existsByTagReal(tag)) {
            log.debug("Tag {} enviada como não cadastrada, mas já está cadastrada (por {})", tag, usuarioAtual())
            return
        }
        if (naoRepo.existsByTag(tag)) {
            log.debug("Tag não cadastrada {} já estava pendente (por {})", tag, usuarioAtual())
            return
        }

        naoRepo.save(
            TagNaoCadastradaEntity(
                tag = tag,
                dataHora = LocalDateTime.now()
            )
        )

        log.info("Tag não cadastrada registrada: {} (por {})", tag, usuarioAtual())
    }

    fun listarNaoCadastradas(): List<TagNaoCadastradaView> =
        naoRepo.findAll()
            .sortedByDescending { it.dataHora }
            .map {
                TagNaoCadastradaView(
                    tag = it.tag,
                    dataHora = it.dataHora
                )
            }

    /* ================= UTIL ================= */

    private fun usuarioAtual(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "desconhecido"

    private fun hashTag(tag: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(tag.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}


