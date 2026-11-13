package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.ClienteEntity
import org.example.model.SituacaoPedido
import org.example.repository.ClienteRepository
import org.example.repository.PedidoLocacaoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class TipoAgenda(val rotulo: String) {
    ENTREGA("Entrega"),
    RETIRADA("Retirada")
}

/** Um compromisso da agenda: entregar ou buscar os equipamentos de um pedido. */
data class ItemAgenda(val tipo: TipoAgenda, val resumo: PedidoResumo, val cliente: ClienteEntity?, val hoje: LocalDate) {
    val pedido get() = resumo.pedido
    val dia: LocalDate? get() = if (tipo == TipoAgenda.ENTREGA) pedido.saidaPrevista else pedido.previsaoDevolucao
    val hora: LocalTime? get() = if (tipo == TipoAgenda.ENTREGA) pedido.entregaHora else pedido.retiradaHora
    val responsavel: String? get() = if (tipo == TipoAgenda.ENTREGA) pedido.entregaResponsavel else pedido.retiradaResponsavel
    val endereco: String? get() = pedido.localEntrega ?: cliente?.endereco

    /** Na entrega, quantos ainda faltam sair; na retirada, quantos estão com o cliente. */
    val quantidade: Int get() = if (tipo == TipoAgenda.ENTREGA) (resumo.quantidade - resumo.saidos).coerceAtLeast(0) else resumo.naRua
    val atrasado: Boolean get() = dia?.isBefore(hoje) == true
    val horaTexto: String get() = hora?.format(HORA) ?: "sem hora"

    /** Chave dos detalhes do roteiro: "12-ENTREGA". */
    val chave: String get() = "${pedido.id}-${tipo.name}"

    private companion object {
        val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** A agenda de um dia, com os atrasados (só quando o dia é hoje) e quantos compromissos há nos próximos dias. */
data class AgendaDia(
    val dia: LocalDate,
    val hoje: LocalDate,
    val entregas: List<ItemAgenda>,
    val retiradas: List<ItemAgenda>,
    val atrasados: List<ItemAgenda>,
    val proximos: List<Pair<LocalDate, Int>>
) {
    val vazia: Boolean get() = entregas.isEmpty() && retiradas.isEmpty() && atrasados.isEmpty()

    /** Para o roteiro: todos os compromissos do dia (atrasados primeiro), por quem leva/busca e pela hora. */
    val porResponsavel: List<Pair<String, List<ItemAgenda>>> get() =
        (atrasados + entregas + retiradas).groupBy { it.responsavel ?: "Sem responsável" }
            .toList().sortedWith(compareBy({ it.first == "Sem responsável" }, { it.first.lowercase() }))
            .map { (r, l) -> r to l.sortedWith(compareBy(nullsLast()) { it.hora }) }
}

/**
 * Agenda de entregas e retiradas (1.24.0). Entregas: pedidos abertos ou em separação com a saída prevista no dia.
 * Retiradas: pedidos com equipamento na rua e a devolução prevista no dia (a locadora busca no cliente). Cada pedido pode
 * ter quem leva e a hora combinada da entrega, e quem busca e a hora da retirada (V38). O roteiro do dia sai em PDF,
 * separado por quem leva, para o motorista.
 */
@Service
class AgendaService(
    private val locacao: LocacaoService,
    private val pedidos: PedidoLocacaoRepository,
    private val clientes: ClienteRepository,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun hoje(): LocalDate = LocalDate.now(relogio)

    @Transactional(readOnly = true)
    fun agenda(dia: LocalDate?): AgendaDia {
        val hoje = hoje()
        val d = dia ?: hoje
        val todos = compromissos(hoje)
        val ordem = compareBy<ItemAgenda, LocalTime?>(nullsLast()) { it.hora }.thenBy { it.pedido.id }
        return AgendaDia(
            dia = d, hoje = hoje,
            entregas = todos.filter { it.tipo == TipoAgenda.ENTREGA && it.dia == d }.sortedWith(ordem),
            retiradas = todos.filter { it.tipo == TipoAgenda.RETIRADA && it.dia == d }.sortedWith(ordem),
            atrasados = if (d != hoje) emptyList() else todos.filter { it.atrasado }.sortedWith(compareBy<ItemAgenda, LocalDate?>(nullsLast()) { it.dia }.then(ordem)),
            proximos = (1..7L).map { hoje.plusDays(it) }.map { dia2 -> dia2 to todos.count { it.dia == dia2 } }
        )
    }

    /** Todos os compromissos em aberto: entregas que faltam e retiradas com equipamento na rua. */
    private fun compromissos(hoje: LocalDate): List<ItemAgenda> {
        val aEntregar = locacao.porSituacao(listOf(SituacaoPedido.ABERTO, SituacaoPedido.EM_SEPARACAO))
            .filter { it.pedido.saidaPrevista != null && it.quantidade > it.saidos }
        val naRua = locacao.porSituacao(listOf(SituacaoPedido.EM_SEPARACAO, SituacaoPedido.ENTREGUE, SituacaoPedido.DEVOLVIDO_PARCIAL))
            .filter { it.naRua > 0 }
        val porId = clientes.findAllById((aEntregar + naRua).map { it.pedido.clienteId }.toSet()).associateBy { it.id }
        return aEntregar.map { ItemAgenda(TipoAgenda.ENTREGA, it, porId[it.pedido.clienteId], hoje) } +
            naRua.map { ItemAgenda(TipoAgenda.RETIRADA, it, porId[it.pedido.clienteId], hoje) }
    }

    /** Quem leva (ou busca) e a hora combinada ("14:30"); vazio apaga. */
    @Transactional
    fun definir(pedidoId: Long, tipo: TipoAgenda, responsavel: String?, hora: String?) {
        val p = locacao.pedido(pedidoId)
        require(!p.situacao.finalizado) { "O pedido nº $pedidoId está ${p.situacao.rotulo.lowercase()}: não entra mais na agenda" }
        val r = responsavel?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 120) { "O responsável pode ter no máximo 120 caracteres" } }
        val h = hora?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalTime.parse(if (it.length == 4) "0$it" else it) }.getOrNull() ?: throw IllegalArgumentException("Hora inválida: $it (use 14:30)")
        }
        if (tipo == TipoAgenda.ENTREGA) { p.entregaResponsavel = r; p.entregaHora = h } else { p.retiradaResponsavel = r; p.retiradaHora = h }
        pedidos.save(p)
        val usuario = historico.autor().first
        val resumo = listOfNotNull(r, h?.toString()).joinToString(", ").ifEmpty { "sem responsável e sem hora" }
        log.info("Agenda do pedido de locação nº {}: {} com {} (por {})", pedidoId, tipo.rotulo.lowercase(), resumo, usuario)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Agenda: ${tipo.rotulo.lowercase()}", "Pedido nº $pedidoId", resumo)
    }

    /** Os equipamentos de cada compromisso do roteiro: na entrega, as linhas que faltam; na retirada, os que estão na rua. */
    @Transactional(readOnly = true)
    fun detalhes(itens: Collection<ItemAgenda>): Map<String, List<String>> = itens.associate { i ->
        val d = locacao.detalhar(i.pedido.id)
        i.chave to if (i.tipo == TipoAgenda.ENTREGA)
            d.linhas.filter { it.faltam > 0 }.map { "${it.faltam} × ${it.linha.descricao}" }
        else d.naRua.map { listOfNotNull(it.patrimonio ?: it.tagReal, it.descricao).joinToString(" · ") }
    }
}
