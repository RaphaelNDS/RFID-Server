package org.example.service

import org.example.model.AcaoHistorico
import org.example.model.EstadoDevolucao
import org.example.model.ResultadoManutencao
import org.example.model.StatusManutencao
import org.example.model.TagEntity
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.InventarioItemRepository
import org.example.repository.ManutencaoRepository
import org.example.repository.PedidoItemRepository
import org.example.repository.PedidoLocacaoRepository
import org.example.repository.TransferenciaItemRepository
import org.example.repository.TransferenciaRepository
import org.example.repository.VendaRepository
import org.example.util.FormatoBr
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

enum class TipoEvento(val rotulo: String, val icone: String) {
    CADASTRO("Cadastro", "mais"),
    ALTERACAO("Alteração", "lapis"),
    SAIDA("Saída para locação", "calendario"),
    DEVOLUCAO("Devolução", "relogio"),
    CONFERENCIA("Conferência da devolução", "ok"),
    MANUTENCAO("Manutenção", "lapis"),
    TRANSFERENCIA("Transferência", "transferir"),
    INVENTARIO("Encontrado no inventário", "sinal"),
    VENDA("Venda", "pacote"),
    EXCLUSAO("Exclusão", "lixeira")
}

/** Um acontecimento na vida do equipamento, com o link para a tela de origem (quando há). */
data class EventoBem(
    val momento: LocalDateTime,
    val tipo: TipoEvento,
    val titulo: String,
    val detalhe: String? = null,
    val por: String? = null,
    val link: String? = null,
    /** Avaria, sem conserto, devolução atrasada: destaque na tela. */
    val alerta: Boolean = false
)

/** Os números do equipamento para decidir se vale manter, consertar ou vender. */
data class ResumoBem(
    val locacoes: Int,
    val clientes: Int,
    val diasAlugado: Long,
    val manutencoes: Int,
    val custoManutencao: BigDecimal,
    val avarias: Int,
    val ultimoCliente: String?
)

data class LinhaDoTempo(val tag: TagEntity, val resumo: ResumoBem, val eventos: List<EventoBem>)

/**
 * Linha do tempo do equipamento (1.16.0): o cadastro e as alterações feitas à mão, cada saída e devolução de locação
 * (com o cliente e a conferência), as manutenções com o custo, as transferências, os inventários que o encontraram e as
 * vendas, numa lista só, do mais novo para o mais antigo. As mudanças de situação que vêm desses fluxos (saída,
 * manutenção, venda) não aparecem de novo como alteração. Só leitura.
 */
@Service
class LinhaDoTempoService(
    private val historico: HistoricoEtiquetaRepository,
    private val pedidoItens: PedidoItemRepository,
    private val pedidos: PedidoLocacaoRepository,
    private val manutencoes: ManutencaoRepository,
    private val transferenciaItens: TransferenciaItemRepository,
    private val transferencias: TransferenciaRepository,
    private val inventarioItens: InventarioItemRepository,
    private val vendas: VendaRepository,
    private val relogio: Clock
) {

    @Transactional(readOnly = true)
    fun montar(tag: TagEntity): LinhaDoTempo {
        val agora = LocalDateTime.now(relogio)
        val eventos = mutableListOf<EventoBem>()

        // Cadastro, exclusão e as alterações feitas à mão (web ou coletor), agrupadas por momento e autor. As que vêm de
        // um fluxo ("Web · Saída do pedido…", "· Manutenção nº…") já aparecem pelo próprio fluxo
        val linhas = historico.findByTagRealOrderByMomentoDescIdDesc(tag.tagReal)
        linhas.filter { it.acao != AcaoHistorico.ALTERACAO }.forEach {
            eventos += EventoBem(it.momento, if (it.acao == AcaoHistorico.CADASTRO) TipoEvento.CADASTRO else TipoEvento.EXCLUSAO,
                it.acao.rotulo, (it.valorNovo ?: it.valorAntigo)?.take(300), "${it.usuario} (${it.origem})")
        }
        linhas.filter { it.acao == AcaoHistorico.ALTERACAO && " · " !in it.origem && it.campo != "Anexo" }
            .groupBy { Triple(it.momento, it.usuario, it.origem) }
            .forEach { (chave, grupo) ->
                eventos += EventoBem(chave.first, TipoEvento.ALTERACAO, "Alterou " + grupo.mapNotNull { it.campo }.distinct().joinToString(", "),
                    grupo.joinToString(" · ") { "${it.campo}: ${it.valorAntigo ?: "—"} → ${it.valorNovo ?: "—"}" }.take(500),
                    "${chave.second} (${chave.third})")
            }
        linhas.filter { it.campo == "Anexo" }.forEach {
            eventos += EventoBem(it.momento, TipoEvento.ALTERACAO, if (it.valorNovo != null) "Anexo enviado" else "Anexo excluído",
                it.valorNovo ?: it.valorAntigo, it.usuario)
        }

        // Locações: saída, devolução e conferência de cada vez que saiu
        val saidas = pedidoItens.findAllByTagRealOrderBySaidaEmDesc(tag.tagReal)
        val dosPedidos = if (saidas.isEmpty()) emptyMap() else pedidos.findAllById(saidas.map { it.pedidoId }.toSet()).associateBy { it.id }
        saidas.forEach { i ->
            val p = dosPedidos[i.pedidoId]
            val link = "/admin/pedidos/${i.pedidoId}"
            eventos += EventoBem(i.saidaEm, TipoEvento.SAIDA, "Saiu no pedido nº ${i.pedidoId}" + (p?.let { " para ${it.cliente}" } ?: ""),
                p?.let { listOfNotNull("devolução prevista ${FormatoBr.data(it.previsaoDevolucao)}", it.localEntrega?.let { l -> "para $l" }).joinToString(" · ") },
                i.saidaPor, link)
            i.devolvidoEm?.let { d ->
                val atrasou = p != null && d.toLocalDate().isAfter(p.previsaoDevolucao)
                eventos += EventoBem(d, TipoEvento.DEVOLUCAO, "Voltou do pedido nº ${i.pedidoId}",
                    if (atrasou) "${Duration.between(p!!.previsaoDevolucao.atStartOfDay(), d.toLocalDate().atStartOfDay()).toDays()} dia(s) depois do previsto" else null,
                    i.devolvidoPor, link, alerta = atrasou)
            }
            i.conferidoEm?.let { c ->
                eventos += EventoBem(c, TipoEvento.CONFERENCIA, "Devolução conferida: ${i.estadoDevolucao?.rotulo ?: "—"}",
                    listOfNotNull(i.observacaoDevolucao, i.manutencaoId?.let { "foi para a manutenção nº $it" }).joinToString(" · ").ifBlank { null },
                    i.conferidoPor, link, alerta = i.estadoDevolucao != null && i.estadoDevolucao != EstadoDevolucao.OK)
            }
        }

        // Manutenções, com o custo e o resultado
        val ordens = manutencoes.findAllByTagIdOrderByDataEnvioDescIdDesc(tag.id)
        ordens.forEach { m ->
            val link = "/admin/manutencoes/${m.id}"
            eventos += EventoBem(m.criadaEm, TipoEvento.MANUTENCAO, "Manutenção nº ${m.id} aberta (${m.tipo.rotulo.lowercase()})",
                listOfNotNull(m.problema, m.fornecedor).joinToString(" · "), m.registradaPor, link)
            m.concluidaEm?.let { fim ->
                eventos += EventoBem(fim.atTime(23, 59), TipoEvento.MANUTENCAO,
                    "Manutenção nº ${m.id} ${m.status.rotulo.lowercase()}" + (m.resultado?.let { ": ${it.rotulo.lowercase()}" } ?: ""),
                    listOfNotNull(m.servicoRealizado, m.custo?.let { FormatoBr.reais(it) }).joinToString(" · ").ifBlank { null },
                    m.concluidaPor, link, alerta = m.resultado == ResultadoManutencao.SEM_CONSERTO)
            }
        }

        // Transferências
        val itensGuia = transferenciaItens.findAllByTagRealOrderByIdDesc(tag.tagReal)
        val guias = if (itensGuia.isEmpty()) emptyMap() else transferencias.findAllById(itensGuia.map { it.transferenciaId }.toSet()).associateBy { it.id }
        itensGuia.forEach { item ->
            val g = guias[item.transferenciaId] ?: return@forEach
            eventos += EventoBem(g.criadaEm, TipoEvento.TRANSFERENCIA, "Transferência nº ${g.id}",
                listOfNotNull(item.localAnterior?.let { "de $it" }, g.localDestino?.let { "para $it" }, g.motivo).joinToString(" · ").ifBlank { null },
                g.registradaPor, "/admin/transferencias/${g.id}")
        }

        // Inventários finalizados que o encontraram (os 50 mais recentes)
        inventarioItens.inventariosDaTag(tag.tagReal, PageRequest.of(0, 50)).forEach { linha ->
            val momento = linha[2] as? LocalDateTime ?: return@forEach
            eventos += EventoBem(momento, TipoEvento.INVENTARIO, "Encontrado no inventário de ${linha[1]}", null, null, "/admin/inventarios/${linha[0]}")
        }

        // Vendas (e as desfeitas)
        vendas.findAllByTagRealOrderByIdDesc(tag.tagReal).forEach { v ->
            eventos += EventoBem(v.criadaEm, TipoEvento.VENDA, "Vendido" + (v.cliente?.let { " para $it" } ?: ""),
                listOfNotNull(v.valor?.let { FormatoBr.reais(it) }, v.observacao).joinToString(" · ").ifBlank { null }, v.registradaPor)
            v.desfeitaEm?.let { eventos += EventoBem(it, TipoEvento.VENDA, "Venda desfeita", null, v.desfeitaPor) }
        }

        val resumo = ResumoBem(
            locacoes = saidas.map { it.pedidoId }.distinct().size,
            clientes = saidas.mapNotNull { dosPedidos[it.pedidoId]?.clienteId }.distinct().size,
            diasAlugado = saidas.sumOf { Duration.between(it.saidaEm, it.devolvidoEm ?: agora).toDays().coerceAtLeast(0) },
            manutencoes = ordens.count { it.status != StatusManutencao.CANCELADA },
            custoManutencao = ordens.mapNotNull { it.custo }.fold(BigDecimal.ZERO, BigDecimal::add),
            avarias = saidas.count { it.estadoDevolucao != null && it.estadoDevolucao != EstadoDevolucao.OK },
            ultimoCliente = saidas.firstOrNull()?.let { dosPedidos[it.pedidoId]?.cliente }
        )
        return LinhaDoTempo(tag, resumo, eventos.sortedByDescending { it.momento })
    }
}
