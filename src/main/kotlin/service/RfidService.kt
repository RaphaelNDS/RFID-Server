package org.example.service

import org.example.util.normalizarEpc
import org.springframework.transaction.annotation.Transactional
import org.example.model.MotivoBaixa
import org.example.model.SituacaoBem
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

/** A etiqueta mudou desde que a tela de edição abriu (versão diferente). */
class EdicaoConcorrenteException(mensagem: String) : IllegalArgumentException(mensagem)

/**
 * Filtros da tela de etiquetas (1.6.0). Por padrão, todas as situações menos BAIXADO. [situacao] escolhe uma só
 * (inclusive BAIXADO); [incluirBaixados] junta os baixados às demais.
 */
data class FiltroEtiquetas(
    val situacao: SituacaoBem? = null,
    val localId: Long? = null,
    val responsavelId: Long? = null,
    val incluirBaixados: Boolean = false
) {
    fun situacoes(): List<SituacaoBem> = when {
        situacao != null -> listOf(situacao)
        incluirBaixados -> SituacaoBem.entries
        else -> SituacaoBem.EDITAVEIS
    }

    val ativo: Boolean get() = situacao != null || localId != null || responsavelId != null || incluirBaixados
}

@Service
class RfidService(
    private val repo: TagRepository,
    private val naoRepo: TagNaoCadastradaRepository,
    private val tipoRepo: TipoEquipamentoRepository,
    private val marcaRepo: MarcaRepository,
    private val modeloRepo: ModeloRepository,
    private val historico: HistoricoEtiquetaService,
    private val relogio: Clock = Clock.systemDefaultZone(),
    // Gestão de patrimônio (1.6.0). Opcionais para os testes que montam o serviço à mão
    private val locaisRepo: LocalRepository? = null,
    private val unidadesRepo: UnidadeRepository? = null,
    private val responsaveisRepo: ResponsavelRepository? = null
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
        validarLocalEResponsavel(req, localAtual = null, responsavelAtual = null)

        val hash = hashTag(req.tag)
        val valor = valorDo(req)

        val entity = TagEntity(
            tagHash = hash,
            tagReal = req.tag,
            codigoInterno = req.codigoInterno,
            patrimonio = req.patrimonio,
            numeroSerie = req.numeroSerie,

            tipoEquipamentoId = req.tipoEquipamentoId,
            marcaId = req.marcaId,
            modeloId = req.modeloId,

            localId = req.localId,
            responsavelId = req.responsavelId,
            situacao = situacaoEscolhida(req.situacao) ?: SituacaoBem.EM_USO,
            valorAquisicao = valor.valor,
            dataAquisicao = valor.data,
            notaFiscal = valor.notaFiscal
        )

        val salva = repo.save(entity)
        historico.registrarCadastro(salva)

        naoRepo.deleteByTag(req.tag)

        log.info(
            "Etiqueta cadastrada: {} (patrimônio: {}) por {}",
            req.tag, req.patrimonio, usuarioAtual()
        )
    }

    fun buscarPorTag(tagBruta: String): TagResponse? {
        val tagReal = normalizarEpc(tagBruta)

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

        val local = tag.localId?.let { id -> locaisRepo?.findById(id)?.orElse(null) }
        return TagResponse(
            codigoInterno = tag.codigoInterno,
            tipo = tipoNome,
            marca = marcaNome,
            modelo = modeloNome,
            patrimonio = tag.patrimonio,
            numeroSerie = tag.numeroSerie,
            situacao = tag.situacao.name,
            situacaoRotulo = tag.situacao.rotulo,
            unidade = local?.unidadeId?.let { id -> unidadesRepo?.findById(id)?.orElse(null)?.nome },
            local = local?.nome,
            responsavel = tag.responsavelId?.let { id -> responsaveisRepo?.findById(id)?.orElse(null)?.nome }
        )
    }

    /**
     * Local e responsável escolhidos existem; os desativados só valem se já eram os do bem (a edição de um bem que
     * ficou num local desativado não obriga a trocar o local).
     */
    private fun validarLocalEResponsavel(req: CadastroRequest, localAtual: Long?, responsavelAtual: Long?) {
        req.localId?.let { id ->
            val local = locaisRepo?.findById(id)?.orElse(null) ?: throw IllegalArgumentException("Local não encontrado")
            require(local.ativo || id == localAtual) { "O local ${local.nome} está desativado" }
        }
        req.responsavelId?.let { id ->
            val r = responsaveisRepo?.findById(id)?.orElse(null) ?: throw IllegalArgumentException("Responsável não encontrado")
            require(r.ativo || id == responsavelAtual) { "O responsável ${r.nome} está desativado" }
        }
    }

    /** A situação escolhida no formulário; vazio = não escolheu. BAIXADO é recusado (a baixa tem tela própria). */
    private fun situacaoEscolhida(texto: String?): SituacaoBem? {
        if (texto.isNullOrBlank()) return null
        val s = SituacaoBem.de(texto) ?: throw IllegalArgumentException("Situação inválida: $texto")
        require(s != SituacaoBem.BAIXADO) { "Para baixar o bem use \"Dar baixa\", com o motivo" }
        return s
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
    fun listarParaAdmin(busca: String?, pagina: Int, filtro: FiltroEtiquetas = FiltroEtiquetas()): Page<TagViewAdmin> =
        buscarParaAdmin(busca, filtro) { consultar -> paginaValida(pagina) { p -> consultar(PageRequest.of(p, TAMANHO_PAGINA, ORDEM_ETIQUETAS)) } }

    /** Todas as etiquetas da busca (exportação e relatórios), na mesma ordem da tela. */
    fun listarTodasParaAdmin(busca: String?, filtro: FiltroEtiquetas = FiltroEtiquetas()): List<TagViewAdmin> =
        buscarParaAdmin(busca, filtro) { consultar -> consultar(Pageable.unpaged(ORDEM_ETIQUETAS)) }.content

    private fun buscarParaAdmin(
        busca: String?,
        filtro: FiltroEtiquetas,
        paginar: ((Pageable) -> Page<TagEntity>) -> Page<TagEntity>
    ): Page<TagViewAdmin> {
        val tipos = tipoRepo.findAll().associate { it.id to it.nome }
        val marcas = marcaRepo.findAll().associate { it.id to it.nome }
        val modelos = modeloRepo.findAll().associate { it.id to it.nome }
        val unidades = unidadesRepo?.findAll()?.associate { it.id to it.nome }.orEmpty()
        val locais = locaisRepo?.findAll()?.associateBy { it.id }.orEmpty()
        val responsaveis = responsaveisRepo?.findAll()?.associate { it.id to it.nome }.orEmpty()

        val padrao = padraoBusca(busca)
        val termo = busca?.trim()?.lowercase().orEmpty()
        // Busca pelo nome do catálogo, do local ou do responsável vira busca pelos ids (cadastros pequenos, já carregados).
        // Lista vazia no IN não é aceita por todos os bancos: -1 não existe
        fun ids(nomes: Map<Long, String>) =
            nomes.filterValues { termo.isNotEmpty() && it.lowercase().contains(termo) }.keys.ifEmpty { setOf(-1L) }

        val resultado = paginar { pedido ->
            repo.buscar(padrao, ids(tipos), ids(marcas), ids(modelos), ids(locais.mapValues { it.value.nome }), ids(responsaveis),
                filtro.situacoes(), filtro.localId ?: -1L, filtro.responsavelId ?: -1L, pedido)
        }

        return resultado.map { tag ->
            val local = tag.localId?.let { locais[it] }
            TagViewAdmin(
                id = tag.id,
                tag = tag.tagReal,
                codigoInterno = tag.codigoInterno,
                tipoNome = tag.tipoEquipamentoId?.let { tipos[it] },
                marcaNome = tag.marcaId?.let { marcas[it] },
                modeloNome = tag.modeloId?.let { modelos[it] },
                patrimonio = tag.patrimonio,
                numeroSerie = tag.numeroSerie,
                unidadeNome = local?.unidadeId?.let { unidades[it] },
                localNome = local?.nome,
                responsavelNome = tag.responsavelId?.let { responsaveis[it] },
                situacao = tag.situacao,
                valorAquisicao = tag.valorAquisicao,
                dataAquisicao = tag.dataAquisicao,
                notaFiscal = tag.notaFiscal
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
        // Outra pessoa salvou depois que esta tela abriu: sem isto, a última a salvar apagava a mudança da outra
        if (pedido.versao != null && pedido.versao != tag.versao) {
            log.warn("Edição da etiqueta {} recusada para {}: alterada por outra pessoa enquanto a tela estava aberta", tag.tagReal, usuarioAtual())
            throw EdicaoConcorrenteException("Esta etiqueta foi alterada por outra pessoa enquanto você editava (veja o histórico). " +
                "Os dados abaixo são os seus: confira e salve de novo para gravar por cima.")
        }
        val req = normalizar(pedido)
        require(req.codigoInterno.isNotBlank()) { "Informe o código interno" }
        validarCatalogo(req.copy(tag = tag.tagReal))
        recusarPatrimonioRepetido(req.patrimonio, idAtual = tag.id)
        validarLocalEResponsavel(req, localAtual = tag.localId, responsavelAtual = tag.responsavelId)
        // Conferida antes de mexer na etiqueta: recusar depois deixaria a entidade alterada na sessão do JPA
        val novaSituacao = if (tag.baixado) null else situacaoEscolhida(req.situacao)
        val valor = valorDo(req)

        val antes = historico.retrato(tag)
        tag.codigoInterno = req.codigoInterno
        tag.patrimonio = req.patrimonio
        tag.numeroSerie = req.numeroSerie
        tag.tipoEquipamentoId = req.tipoEquipamentoId
        tag.marcaId = req.marcaId
        tag.modeloId = req.modeloId
        tag.localId = req.localId
        tag.responsavelId = req.responsavelId
        // Bem baixado continua baixado (desfazer é pela tela da baixa); vazio = a situação atual
        novaSituacao?.let { tag.situacao = it }
        tag.valorAquisicao = valor.valor
        tag.dataAquisicao = valor.data
        tag.notaFiscal = valor.notaFiscal
        repo.save(tag)

        val mudaram = historico.registrarAlteracao(tag, antes)
        if (mudaram.isEmpty()) {
            log.info("Etiqueta {} salva sem mudanças por {}", tag.tagReal, usuarioAtual())
        } else {
            log.info("Etiqueta {} (patrimônio {}) alterada por {}: {}", tag.tagReal, tag.patrimonio, usuarioAtual(), mudaram.joinToString())
        }
        return mudaram
    }

    private data class ValorInformado(val valor: java.math.BigDecimal?, val data: java.time.LocalDate?, val notaFiscal: String?)

    /**
     * Valor, data de aquisição e nota fiscal do pedido (1.8.0), já conferidos: valor não negativo, data que não está
     * no futuro e nota de até 60 caracteres. Tudo opcional (vazio = sem valor).
     */
    private fun valorDo(req: CadastroRequest): ValorInformado {
        val valor = org.example.util.FormatoBr.lerValor(req.valorAquisicao)
        val data = org.example.util.FormatoBr.lerData(req.dataAquisicao)
        require(data == null || !data.isAfter(java.time.LocalDate.now(relogio))) {
            "A data de aquisição ${org.example.util.FormatoBr.data(data)} está no futuro"
        }
        val nota = req.notaFiscal?.trim()?.takeIf { it.isNotEmpty() }
        require(nota == null || nota.length <= 60) { "A nota fiscal passa de 60 caracteres (tem ${nota!!.length})" }
        return ValorInformado(valor, data, nota)
    }

    private fun normalizar(req: CadastroRequest): CadastroRequest {
        val limpo = req.copy(
            tag = normalizarEpc(req.tag),
            codigoInterno = req.codigoInterno.trim(),
            patrimonio = req.patrimonio.trim(),
            numeroSerie = req.numeroSerie.trim()
        )
        // As colunas são varchar(255): o banco recusaria com uma mensagem confusa e a tela perderia o formulário
        mapOf("etiqueta (EPC)" to limpo.tag, "código interno" to limpo.codigoInterno, "patrimônio" to limpo.patrimonio,
            "nº de série" to limpo.numeroSerie).forEach { (campo, valor) ->
            require(valor.length <= TAMANHO_MAXIMO_CAMPO) { "O campo $campo passa de $TAMANHO_MAXIMO_CAMPO caracteres (tem ${valor.length})" }
        }
        return limpo
    }

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

    /**
     * Baixa patrimonial (1.6.0): o bem sai das listas, do esperado do inventário e do relatório "não vistas", mas
     * continua no cadastro (filtro "Baixados"), no histórico e na auditoria. Diferente de excluir.
     */
    @Transactional
    fun darBaixa(id: Long, motivo: MotivoBaixa, data: java.time.LocalDate?, observacao: String?, origem: String? = null): TagEntity {
        val tag = buscarPorId(id)
        require(!tag.baixado) { "Este bem já foi baixado em ${tag.baixaEm} (${tag.baixaMotivo?.rotulo})" }
        val hoje = java.time.LocalDate.now(relogio)
        val dia = data ?: hoje
        require(!dia.isAfter(hoje)) { "A data da baixa não pode ser no futuro" }
        val obs = observacao?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 500) { "A observação pode ter no máximo 500 caracteres" } }

        val antes = historico.retrato(tag)
        tag.situacao = SituacaoBem.BAIXADO
        tag.baixaMotivo = motivo
        tag.baixaEm = dia
        tag.baixaObservacao = obs
        tag.baixaPor = usuarioAtual()
        repo.save(tag)
        historico.registrarAlteracao(tag, antes, origem)
        log.warn("Baixa do bem {} (patrimônio {}) por {}: {} em {}{}", tag.tagReal, tag.patrimonio, usuarioAtual(), motivo.rotulo, dia,
            obs?.let { " ($it)" } ?: "")
        return tag
    }

    /** Desfaz a baixa (lançada por engano): o bem volta "Em uso". Fica no histórico. */
    @Transactional
    fun desfazerBaixa(id: Long): TagEntity {
        val tag = buscarPorId(id)
        require(tag.baixado) { "Este bem não está baixado" }
        val antes = historico.retrato(tag)
        tag.situacao = SituacaoBem.EM_USO
        tag.baixaMotivo = null
        tag.baixaEm = null
        tag.baixaObservacao = null
        tag.baixaPor = null
        repo.save(tag)
        historico.registrarAlteracao(tag, antes)
        log.warn("Baixa do bem {} (patrimônio {}) desfeita por {}", tag.tagReal, tag.patrimonio, usuarioAtual())
        return tag
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

    fun salvarNaoCadastrada(tagBruta: String) {
        val tag = normalizarEpc(tagBruta)

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

        // Chaves do mapa na forma padrão: quem chama procura com normalizarEpc()
        val encontradas = repo.findAllByTagRealIn(tags.map(::normalizarEpc).toSet())
        val nomes = NomesCatalogo(
            tipos = tipoRepo.findAllById(encontradas.mapNotNull { it.tipoEquipamentoId }.toSet()).associate { it.id to it.nome },
            marcas = marcaRepo.findAllById(encontradas.mapNotNull { it.marcaId }.toSet()).associate { it.id to it.nome },
            modelos = modeloRepo.findAllById(encontradas.mapNotNull { it.modeloId }.toSet()).associate { it.id to it.nome }
        )
        return encontradas.associateBy { it.tagReal } to nomes
    }

    /**
     * Os dados da etiqueta para o coletor, com os nomes do catálogo já carregados. Desde a 1.6.2 leva a situação (o
     * app avisa no cartão da leitura quando o bem lido está baixado ou extraviado), com os mesmos nomes da ficha.
     */
    fun resposta(tag: TagEntity, nomes: NomesCatalogo) = TagResponse(
        codigoInterno = tag.codigoInterno,
        tipo = tag.tipoEquipamentoId?.let { nomes.tipos[it] },
        marca = tag.marcaId?.let { nomes.marcas[it] },
        modelo = tag.modeloId?.let { nomes.modelos[it] },
        patrimonio = tag.patrimonio,
        numeroSerie = tag.numeroSerie,
        situacao = tag.situacao.name,
        situacaoRotulo = tag.situacao.rotulo
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
            // Só o índice único quer dizer "já pendente"; outro erro de integridade sobe e o lote marca ERRO
            if (!org.example.util.violacaoDeUnicidade(e)) {
                log.error("Tag não cadastrada {} recusada pelo banco por erro de integridade (não é envio simultâneo)", tag, e)
                throw e
            }
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

        /** Tamanho das colunas de texto das etiquetas (varchar 255). */
        const val TAMANHO_MAXIMO_CAMPO = 255

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


