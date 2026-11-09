package org.example.service

import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.transaction.annotation.Transactional
import org.example.model.InventarioEntity
import org.example.model.InventarioItemEntity
import org.example.model.LocalEntity
import org.example.model.StatusInventario
import org.example.repository.InventarioItemRepository
import org.example.repository.InventarioRepository
import org.example.repository.JustificativaInventarioRepository
import org.example.repository.LocalRepository
import org.example.model.JustificativaInventarioEntity
import org.example.model.MotivoFalta
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/** Linha da lista de inventários (tela web). */
data class InventarioResumo(
    val id: String,
    val local: String,
    val dispositivo: String,
    val status: StatusInventario,
    val iniciadoEm: LocalDateTime,
    val finalizadoEm: LocalDateTime?,
    val totalLidas: Long,
    val operador: String? = null,
    val cadastradas: Long,
    /** Id do local (1.6.1: cadastrar a etiqueta lida já neste local). */
    val localId: Long? = null
) {
    val naoCadastradas: Long get() = totalLidas - cadastradas
}

data class InventarioDetalhe(
    val resumo: InventarioResumo,
    val itens: List<InventarioItemEntity>
)

/** Mesmo local com grafia diferente (" Sala  101 ") vira um só; a busca no banco ignora maiúsculas. */
fun limparNomeLocal(nome: String): String = nome.trim().replace(Regex("\\s+"), " ")

data class ItemLido(val tag: String, val lidoEm: OffsetDateTime?)

data class ResultadoItens(val recebidos: Int, val novos: Int)

/** Uma justificativa como chega do coletor. [motivo]: o código (EMPRESTADO) ou o nome ("Emprestado"). */
data class JustificativaRecebida(val tag: String = "", val motivo: String = "", val observacao: String? = null)

/** [gravadas]: novas ou alteradas (o reenvio igual não conta). [invalidas]: etiquetas ignoradas por etiqueta ou motivo inválido. */
data class ResultadoJustificativas(val recebidas: Int, val gravadas: Int, val invalidas: List<String>)

/**
 * Inventário por local feito pelo coletor: abre (num local), recebe as etiquetas lidas e finaliza.
 * Todas as operações podem ser repetidas sem duplicar nada, porque o coletor reenvia o que não teve
 * confirmação (ex.: a rede caiu depois de o servidor gravar).
 */
@Service
class InventarioService(
    private val locais: LocalRepository,
    private val inventarios: InventarioRepository,
    private val itens: InventarioItemRepository,
    private val rfidService: RfidService,
    private val justificativas: JustificativaInventarioRepository,
    private val relogio: Clock = Clock.systemDefaultZone(),
    // 1.6.0: o local do bem acompanha o inventário finalizado (opcionais para os testes que montam o serviço à mão)
    private val tags: org.example.repository.TagRepository? = null,
    private val historico: HistoricoEtiquetaService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Sugestões do coletor: só os locais ativos (o desativado na tela Locais some da lista do app). */
    fun listarLocais(): List<String> = locais.findAllByAtivoTrueOrderByNomeAsc().map { it.nome }

    @Transactional
    fun abrir(id: String, nomeLocal: String, iniciadoEm: OffsetDateTime?, dispositivo: String, operador: String? = null): InventarioEntity {
        validarId(id)

        inventarios.findById(id).orElse(null)?.let {
            exigirMesmoColetor(it, dispositivo, "abertura")
            log.debug("Inventário {} reenviado pelo dispositivo {}: já existia", id, dispositivo)
            return it
        }

        val local = localPorNome(nomeLocal)
        val inventario = inventarios.save(
            InventarioEntity(
                id = id,
                localId = local.id,
                dispositivo = dispositivo,
                iniciadoEm = horaDoColetor(iniciadoEm, "abertura do inventário $id"),
                operador = operador
            )
        )

        log.info("Inventário {} aberto no local '{}' pelo dispositivo {} (operador {})", id, local.nome, dispositivo, operador ?: "—")
        return inventario
    }

    @Transactional
    fun registrarItens(id: String, lidosBrutos: List<ItemLido>, dispositivo: String, operador: String? = null): ResultadoItens {
        val inventario = buscar(id)
        exigirMesmoColetor(inventario, dispositivo, "envio de etiquetas")

        require(lidosBrutos.size <= MAXIMO_ITENS_POR_ENVIO) { "Envie no máximo $MAXIMO_ITENS_POR_ENVIO etiquetas por vez" }
        require(lidosBrutos.all { it.tag.isNotBlank() }) { "Informe a TAG RFID de todos os itens" }

        // EPC na forma padrão (como no lote e no cadastro): " abc" não batia com o cadastro "ABC". Etiqueta acima do
        // tamanho da coluna sai só ela: antes o banco recusava o envio inteiro (até 500 itens) com erro 400
        val (lidos, longas) = lidosBrutos.map { it.copy(tag = org.example.util.normalizarEpc(it.tag)) }
            .partition { it.tag.length <= LeituraLoteService.TAMANHO_MAXIMO_TAG }
        if (longas.isNotEmpty()) {
            log.warn("Inventário {}: {} etiqueta(s) com mais de {} caracteres ignorada(s) no envio do dispositivo {}",
                id, longas.size, LeituraLoteService.TAMANHO_MAXIMO_TAG, dispositivo)
        }

        if (inventario.status == StatusInventario.FINALIZADO) {
            // Leituras feitas antes da finalização que chegaram depois dela (ex.: reenvio): aceita
            log.info("Inventário {} já finalizado recebendo {} etiqueta(s) atrasada(s) do dispositivo {}", id, lidos.size, dispositivo)
        }

        // Em bloco, como o lote de leituras: só as tags deste envio que já estão no inventário, e as etiquetas e o
        // catálogo delas numa consulta cada (antes: todas as tags do inventário a cada envio e 4 consultas por item)
        val doEnvio = lidos.distinctBy { it.tag }
        val jaRegistradas = if (doEnvio.isEmpty()) emptySet() else itens.tagsJaNoInventario(id, doEnvio.map { it.tag }).toSet()
        val novas = doEnvio.filter { it.tag !in jaRegistradas }
        val (cadastradas, nomes) = rfidService.carregarParaLote(novas.map { it.tag })

        itens.saveAll(novas.map { lido ->
            val cadastro = cadastradas[lido.tag]?.let { rfidService.resposta(it, nomes) }
            InventarioItemEntity(
                inventarioId = id,
                tag = lido.tag,
                lidoEm = horaDoColetor(lido.lidoEm, "leitura da tag ${lido.tag} no inventário $id"),
                cadastrada = cadastro != null,
                codigoInterno = cadastro?.codigoInterno,
                patrimonio = cadastro?.patrimonio,
                tipo = cadastro?.tipo,
                marca = cadastro?.marca,
                modelo = cadastro?.modelo,
                operador = operador
            )
        })
        val novos = novas.size

        log.info(
            "Inventário {}: {} etiqueta(s) recebida(s) do dispositivo {}, {} nova(s)",
            id, lidos.size, dispositivo, novos
        )
        return ResultadoItens(recebidos = lidos.size, novos = novos)
    }

    @Transactional
    fun finalizar(id: String, finalizadoEm: OffsetDateTime?, dispositivo: String): InventarioEntity {
        val inventario = buscar(id)
        exigirMesmoColetor(inventario, dispositivo, "finalização")

        if (inventario.status == StatusInventario.FINALIZADO) {
            log.debug("Finalização do inventário {} reenviada pelo dispositivo {}: já estava finalizado", id, dispositivo)
            return inventario
        }

        inventario.status = StatusInventario.FINALIZADO
        inventario.finalizadoEm = horaDoColetor(finalizadoEm, "finalização do inventário $id")

        val total = itens.countByInventarioId(id)
        val atualizados = atualizarLocalDosBens(inventario)
        log.info("Inventário {} finalizado pelo dispositivo {}: {} etiqueta(s) lida(s){}", id, dispositivo, total,
            if (atualizados > 0) "; $atualizados bem(ns) com o local atualizado para o do inventário" else "")
        return inventarios.save(inventario)
    }

    /**
     * Gestão de patrimônio (1.6.0): o bem cadastrado encontrado no inventário finalizado passa a estar no local do
     * inventário (transferência pelo inventário), com a mudança no histórico. Extraviado que foi encontrado volta "Em
     * uso". O baixado não muda: aparece no inventário, mas continua baixado (para desfazer, é pela tela da baixa).
     */
    private fun atualizarLocalDosBens(inventario: InventarioEntity): Int {
        val repo = tags ?: return 0
        val lidas = itens.tagsDoInventario(inventario.id)
        if (lidas.isEmpty()) return 0
        var atualizados = 0
        lidas.chunked(5_000).flatMap { repo.findAllByTagRealIn(it) }.filter { !it.baixado }.forEach { tag ->
            val mudarLocal = tag.localId != inventario.localId
            val achado = tag.situacao == org.example.model.SituacaoBem.EXTRAVIADO
            if (!mudarLocal && !achado) return@forEach
            val antes = historico?.retrato(tag)
            tag.localId = inventario.localId
            if (achado) tag.situacao = org.example.model.SituacaoBem.EM_USO
            repo.save(tag)
            if (antes != null) historico.registrarAlteracao(tag, antes)
            atualizados++
        }
        return atualizados
    }

    /**
     * Justificativas dos bens que faltaram (1.5.0), marcadas no coletor na finalização. Aceitas também depois de
     * finalizado (vêm pela fila do app). Idempotente: o reenvio da mesma etiqueta atualiza a justificativa dela.
     * Item com etiqueta ou motivo inválido é ignorado e devolvido em [ResultadoJustificativas.invalidas], para o app
     * não reenviar para sempre.
     */
    @Transactional
    fun registrarJustificativas(id: String, recebidas: List<JustificativaRecebida>, dispositivo: String, operador: String? = null): ResultadoJustificativas {
        require(recebidas.size <= MAXIMO_JUSTIFICATIVAS) { "No máximo $MAXIMO_JUSTIFICATIVAS justificativas por envio (recebidas ${recebidas.size})" }
        val inventario = buscar(id)
        exigirMesmoColetor(inventario, dispositivo, "justificativas")

        val existentes = justificativas.findByInventarioId(id).associateBy { it.tag }.toMutableMap()
        val agora = LocalDateTime.now(relogio)
        val invalidas = mutableListOf<String>()
        var gravadas = 0

        for (r in recebidas) {
            val tag = org.example.util.normalizarEpc(r.tag)
            val motivo = MotivoFalta.de(r.motivo)
            if (tag.isEmpty() || tag.length > LeituraLoteService.TAMANHO_MAXIMO_TAG || motivo == null) {
                invalidas += r.tag.take(60)
                continue
            }
            val observacao = r.observacao?.filter { !it.isISOControl() }?.trim()?.take(500)?.ifEmpty { null }
            val atual = existentes[tag]
            if (atual != null) {
                if (atual.motivo == motivo && atual.observacao == observacao) continue   // reenvio igual
                atual.motivo = motivo
                atual.observacao = observacao
                atual.operador = operador ?: atual.operador
                atual.registradaEm = agora
                justificativas.save(atual)
            } else {
                existentes[tag] = justificativas.save(JustificativaInventarioEntity(
                    inventarioId = id, tag = tag, motivo = motivo, observacao = observacao, operador = operador, registradaEm = agora))
            }
            gravadas++
        }

        if (gravadas > 0) {
            log.info("Inventário {}: {} justificativa(s) de bem não encontrado recebida(s) do dispositivo {} ({})", id, gravadas, dispositivo,
                recebidas.mapNotNull { MotivoFalta.de(it.motivo) }.groupingBy { it }.eachCount().entries.joinToString { "${it.key.rotulo} ${it.value}" })
        }
        if (invalidas.isNotEmpty()) log.warn("Inventário {}: {} justificativa(s) com etiqueta ou motivo inválido ignorada(s) (dispositivo {})", id, invalidas.size, dispositivo)
        return ResultadoJustificativas(recebidas.size, gravadas, invalidas)
    }

    /** Justificativas do inventário por etiqueta (tela de detalhe e exportação). */
    fun justificativasDo(id: String): Map<String, JustificativaInventarioEntity> = justificativas.findByInventarioId(id).associateBy { it.tag }

    /**
     * Tela de inventários: uma página (mais recentes primeiro), do local escolhido ou de todos. [pagina] começa
     * em 0. Antes vinham todos de uma vez, e a contagem de itens somava os de todos os inventários.
     */
    fun listar(localId: Long? = null, pagina: Int = 0): Page<InventarioResumo> {
        val ordem = Sort.by(Sort.Direction.DESC, "iniciadoEm").and(Sort.by(Sort.Direction.DESC, "id"))
        val resultado = RfidService.paginaValida(pagina) { p ->
            val pedido = PageRequest.of(p, RfidService.TAMANHO_PAGINA, ordem)
            if (localId == null) inventarios.findAllBy(pedido) else inventarios.findByLocalId(localId, pedido)
        }

        val lista = resultado.content
        val nomes = locais.findAllById(lista.map { it.localId }.toSet()).associate { it.id to it.nome }
        val contagens = if (lista.isEmpty()) emptyMap() else itens.contarPorInventario(lista.map { it.id }).associate {
            (it[0] as String) to Pair((it[1] as Number).toLong(), (it[2] as Number?)?.toLong() ?: 0L)
        }

        return resultado.map { resumo(it, nomes[it.localId].orEmpty(), contagens[it.id]) }
    }

    fun detalhar(id: String): InventarioDetalhe {
        val inventario = buscar(id)
        val lista = itens.findByInventarioIdOrderByLidoEmAsc(id)
        val nome = locais.findById(inventario.localId).map { it.nome }.orElse("")

        return InventarioDetalhe(
            resumo = resumo(inventario, nome, Pair(lista.size.toLong(), lista.count { it.cadastrada }.toLong())),
            itens = lista
        )
    }

    fun locaisCadastrados(): List<LocalEntity> = locais.findAllByOrderByNomeAsc()

    // ================= INTERNOS =================

    /**
     * O inventário é do coletor que o abriu: outro coletor (por engano de id ou de propósito) não envia etiquetas
     * para ele nem o finaliza. 403: o app trata como recusa definitiva e não fica reenviando.
     */
    private fun exigirMesmoColetor(inventario: InventarioEntity, dispositivo: String, acao: String) {
        if (inventario.dispositivo == dispositivo) return
        log.warn("Inventário {} ({}) recusado: é do coletor {}, e o pedido veio do coletor {}", inventario.id, acao, inventario.dispositivo, dispositivo)
        throw ResponseStatusException(HttpStatus.FORBIDDEN, "Este inventário foi aberto por outro coletor (${inventario.dispositivo})")
    }

    private fun resumo(i: InventarioEntity, local: String, contagem: Pair<Long, Long>?) = InventarioResumo(
        id = i.id,
        local = local,
        dispositivo = i.dispositivo,
        status = i.status,
        iniciadoEm = i.iniciadoEm,
        finalizadoEm = i.finalizadoEm,
        totalLidas = contagem?.first ?: 0,
        cadastradas = contagem?.second ?: 0,
        operador = i.operador,
        localId = i.localId
    )

    private fun buscar(id: String): InventarioEntity =
        inventarios.findById(id).orElseThrow { NoSuchElementException("Inventário $id não encontrado") }

    /** Mesmo local com grafia diferente ("sala 101", " Sala 101 ") vira um só. */
    private fun localPorNome(nome: String): LocalEntity {
        val limpo = limparNomeLocal(nome)

        require(limpo.isNotEmpty()) { "Informe o local do inventário" }
        require(limpo.length <= TAMANHO_MAXIMO_LOCAL) { "O nome do local pode ter no máximo $TAMANHO_MAXIMO_LOCAL caracteres" }

        return locais.findFirstByNomeIgnoreCaseOrderByIdAsc(limpo).orElseGet {
            log.info("Novo local cadastrado pelo inventário: '{}'", limpo)
            locais.save(LocalEntity(nome = limpo))
        }
    }

    private fun validarId(id: String) {
        try {
            UUID.fromString(id)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Identificador do inventário inválido: use um UUID")
        }
    }

    /**
     * Hora informada pelo coletor (pode ter sido offline), no fuso do servidor. Sem ela, ou no
     * futuro (relógio do coletor adiantado), vale a hora do recebimento.
     */
    private fun horaDoColetor(hora: OffsetDateTime?, descricao: String): LocalDateTime {
        val agora = LocalDateTime.now(relogio)
        if (hora == null) return agora

        val local = org.example.util.HoraColetor.noFusoDoServidor(hora)

        if (org.example.util.HoraColetor.noFuturo(local, agora)) {
            log.warn("Data no futuro na {} ({}): relógio do coletor adiantado? Usando a hora do recebimento", descricao, hora)
            return agora
        }

        return local
    }

    private companion object {
        const val MAXIMO_ITENS_POR_ENVIO = 500
        const val MAXIMO_JUSTIFICATIVAS = 2000
        const val TAMANHO_MAXIMO_LOCAL = 120
    }
}
