package org.example.service

import org.springframework.transaction.annotation.Transactional
import org.example.model.TagEntity
import org.example.model.TagNaoCadastradaEntity
import org.example.repository.*
import org.example.request.CadastroRequest
import org.example.request.TagNaoCadastradaView
import org.example.response.TagResponse
import org.springframework.stereotype.Service
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.security.MessageDigest
import org.example.response.TagViewAdmin
import java.time.Clock
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
    private val historico: HistoricoEtiquetaService,
    private val relogio: Clock = Clock.systemDefaultZone()
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

    /**
     * Tela de etiquetas: uma página (mais recentes primeiro), com busca no servidor por EPC, código, patrimônio,
     * série ou nome do tipo/marca/modelo. Antes vinham todas de uma vez e a busca era só no navegador, o que
     * fica pesado com milhares de etiquetas. [pagina] começa em 0. Carrega o catálogo uma vez só.
     */
    fun listarParaAdmin(busca: String?, pagina: Int): Page<TagViewAdmin> =
        buscarParaAdmin(busca) { consultar -> paginaValida(pagina) { p -> consultar(PageRequest.of(p, TAMANHO_PAGINA, ORDEM_ETIQUETAS)) } }

    /** Todas as etiquetas da busca (exportação e relatórios), na mesma ordem da tela. */
    fun listarTodasParaAdmin(busca: String?): List<TagViewAdmin> =
        buscarParaAdmin(busca) { consultar -> consultar(Pageable.unpaged(ORDEM_ETIQUETAS)) }.content

    private fun buscarParaAdmin(
        busca: String?,
        paginar: ((Pageable) -> Page<TagEntity>) -> Page<TagEntity>
    ): Page<TagViewAdmin> {
        val tipos = tipoRepo.findAll().associate { it.id to it.nome }
        val marcas = marcaRepo.findAll().associate { it.id to it.nome }
        val modelos = modeloRepo.findAll().associate { it.id to it.nome }

        val padrao = padraoBusca(busca)
        val termo = busca?.trim()?.lowercase().orEmpty()
        // Busca pelo nome do catálogo vira busca pelos ids (o catálogo é pequeno e já está carregado).
        // Lista vazia no IN não é aceita por todos os bancos: -1 não existe
        fun ids(nomes: Map<Long, String>) =
            nomes.filterValues { termo.isNotEmpty() && it.lowercase().contains(termo) }.keys.ifEmpty { setOf(-1L) }

        val resultado = paginar { pedido -> repo.buscar(padrao, ids(tipos), ids(marcas), ids(modelos), pedido) }

        return resultado.map { tag ->
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
                dataHora = LocalDateTime.now(relogio)
            )
        )

        log.info("Tag não cadastrada registrada: {} (por {})", tag, usuarioAtual())
    }

    /** Tela Pendentes: uma página (mais recentes primeiro), com busca pelo EPC. [pagina] começa em 0. */
    fun listarNaoCadastradas(busca: String?, pagina: Int): Page<TagNaoCadastradaView> {
        val ordem = Sort.by(Sort.Direction.DESC, "dataHora").and(Sort.by(Sort.Direction.DESC, "id"))
        return paginaValida(pagina) { p ->
            naoRepo.buscar(padraoBusca(busca), PageRequest.of(p, TAMANHO_PAGINA, ordem))
        }.map { TagNaoCadastradaView(tag = it.tag, dataHora = it.dataHora) }
    }

    /* ================= LOTE DO COLETOR ================= */

    /**
     * Lote: as etiquetas cadastradas entre [tags] (por EPC) e os nomes do catálogo delas, numa consulta para as
     * etiquetas e uma para cada parte do catálogo, para o lote inteiro (antes eram 4 consultas por leitura).
     */
    fun carregarParaLote(tags: Collection<String>): Pair<Map<String, TagEntity>, NomesCatalogo> {
        if (tags.isEmpty()) return emptyMap<String, TagEntity>() to NomesCatalogo(emptyMap(), emptyMap(), emptyMap())

        val encontradas = repo.findAllByTagRealIn(tags)
        val nomes = NomesCatalogo(
            tipos = tipoRepo.findAllById(encontradas.mapNotNull { it.tipoEquipamentoId }.toSet()).associate { it.id to it.nome },
            marcas = marcaRepo.findAllById(encontradas.mapNotNull { it.marcaId }.toSet()).associate { it.id to it.nome },
            modelos = modeloRepo.findAllById(encontradas.mapNotNull { it.modeloId }.toSet()).associate { it.id to it.nome }
        )
        return encontradas.associateBy { it.tagReal } to nomes
    }

    /** Os dados da etiqueta para o coletor, com os nomes do catálogo já carregados. */
    fun resposta(tag: TagEntity, nomes: NomesCatalogo) = TagResponse(
        codigoInterno = tag.codigoInterno,
        tipo = tag.tipoEquipamentoId?.let { nomes.tipos[it] },
        marca = tag.marcaId?.let { nomes.marcas[it] },
        modelo = tag.modeloId?.let { nomes.modelos[it] },
        patrimonio = tag.patrimonio,
        numeroSerie = tag.numeroSerie
    )

    /** Lote: quais destas tags já estão em "Não cadastradas" (uma consulta). */
    fun jaPendentes(tags: Collection<String>): Set<String> =
        if (tags.isEmpty()) emptySet() else naoRepo.jaPendentes(tags).toSet()

    /**
     * Lote: grava como não cadastrada uma tag que o lote já conferiu (não está cadastrada nem pendente).
     * Outro envio ao mesmo tempo pode ter gravado antes: o índice único recusa e conta como já pendente.
     */
    fun registrarPendenteDoLote(tag: String) {
        try {
            naoRepo.saveAndFlush(TagNaoCadastradaEntity(tag = tag, dataHora = LocalDateTime.now(relogio)))
            log.info("Tag não cadastrada registrada: {} (por {})", tag, usuarioAtual())
        } catch (e: DataIntegrityViolationException) {
            log.debug("Tag não cadastrada {} já estava pendente (envio simultâneo)", tag)
        }
    }

    /* ================= UTIL ================= */

    private fun usuarioAtual(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "desconhecido"

    private fun hashTag(tag: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(tag.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Linhas por página nas telas de etiquetas e pendentes. */
        const val TAMANHO_PAGINA = 50

        /** Etiquetas: as cadastradas por último primeiro. */
        private val ORDEM_ETIQUETAS: Sort = Sort.by(Sort.Direction.DESC, "id")

        /**
         * Padrão do LIKE para a busca: "%termo%" em minúsculas, com % e _ do próprio texto escapados (a consulta
         * usa ESCAPE '\'). Busca vazia = "" (a consulta traz tudo).
         */
        fun padraoBusca(busca: String?): String {
            val termo = busca?.trim()?.lowercase().orEmpty()
            if (termo.isEmpty()) return ""
            return "%" + termo.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }

        /** Página pedida além da última (ex.: link antigo depois de excluir etiquetas): mostra a última. */
        fun <T> paginaValida(pagina: Int, buscar: (Int) -> Page<T>): Page<T> {
            val resultado = buscar(pagina.coerceAtLeast(0))
            if (resultado.content.isEmpty() && resultado.number > 0 && resultado.totalPages > 0) {
                return buscar(resultado.totalPages - 1)
            }
            return resultado
        }
    }
}


