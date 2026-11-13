package org.example.service

import org.example.util.FormatoBr
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Resumo diário da locação pelos avisos (1.20.0): pedidos com devolução atrasada e manutenções com retorno vencido.
 * Sai uma vez por dia (rfid.alertas.locacao-cron, padrão 8h) e só quando há algo atrasado. Vai pelos canais ligados
 * (email e/ou Telegram); sem nenhum, fica só no log.
 */
@Service
class AvisoLocacaoService(
    private val painel: PainelService,
    private val alertas: AlertaService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${rfid.alertas.locacao-cron:0 0 8 * * *}")
    fun resumoDiario() {
        runCatching { montar() }
            .onSuccess { r -> r?.let { (assunto, texto) -> alertas.informar(assunto, texto) } ?: log.info("Resumo diário da locação: nada atrasado") }
            .onFailure { log.error("Resumo diário da locação falhou: {}", it.message, it) }
    }

    /** Assunto e texto do resumo, ou null quando não há nada atrasado. */
    fun montar(): Pair<String, String>? {
        val p = painel.painel()
        if (p.atrasados.isEmpty() && p.manutencoesAtrasadas.isEmpty()) return null
        val partes = mutableListOf<String>()
        if (p.atrasados.isNotEmpty()) {
            partes += "Devoluções atrasadas (${p.atrasados.size}):\n" + p.atrasados.take(MAXIMO).joinToString("\n") {
                "• Pedido nº ${it.pedido.id} — ${it.pedido.cliente}: ${it.naRua} equipamento(s), previsto para ${FormatoBr.data(it.pedido.previsaoDevolucao)} (${it.diasAtraso} dia(s) de atraso)"
            } + resto(p.atrasados.size)
        }
        if (p.manutencoesAtrasadas.isNotEmpty()) {
            partes += "Manutenções com retorno vencido (${p.manutencoesAtrasadas.size}):\n" + p.manutencoesAtrasadas.take(MAXIMO).joinToString("\n") {
                "• Ordem nº ${it.id}" + (it.fornecedor?.let { f -> " — $f" } ?: "") + (it.previsaoRetorno?.let { d -> ", retorno previsto em ${FormatoBr.data(d)}" } ?: "")
            } + resto(p.manutencoesAtrasadas.size)
        }
        val assunto = listOfNotNull(
            p.atrasados.size.takeIf { it > 0 }?.let { "$it devolução(ões) atrasada(s)" },
            p.manutencoesAtrasadas.size.takeIf { it > 0 }?.let { "$it manutenção(ões) atrasada(s)" }
        ).joinToString(" e ")
        return "Locação: $assunto" to partes.joinToString("\n\n") + "\n\nDetalhes na tela Início do sistema."
    }

    private fun resto(total: Int) = if (total > MAXIMO) "\n• … e mais ${total - MAXIMO}" else ""

    private companion object {
        const val MAXIMO = 20
    }
}
