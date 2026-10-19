package org.example.service

import org.example.response.TagResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Uma leitura do lote: a tag e quando o coletor a leu. */
data class ItemLote(val tag: String = "", val lidoEm: OffsetDateTime? = null)

enum class StatusItemLote {
    /** Tag cadastrada: leitura registrada (ou já estava, num reenvio). Volta com os dados da tag. */
    CADASTRADA,
    /** Tag sem cadastro: registrada em "Não cadastradas". */
    NAO_CADASTRADA,
    /** Recusa definitiva deste item (tag vazia, sessão que não valia naquele momento): o coletor não reenvia. */
    RECUSADA,
    /** Falha inesperada no servidor: o coletor tenta de novo depois. */
    ERRO
}

data class ResultadoItemLote(
    val indice: Int,
    val tag: String,
    val status: StatusItemLote,
    val dados: TagResponse? = null,
    val mensagem: String? = null
)

/**
 * Recebe de uma vez várias leituras do coletor (POST /api/android/leituras/lote) e devolve o resultado de cada
 * uma: o app deixava de fazer 2 requisições por tag (consultar + registrar), o que numa fila de centenas de
 * leituras e Wi-Fi fraco levava minutos.
 *
 * Cada item é independente: um item ruim não derruba o lote. As regras são as mesmas das rotas individuais:
 * a sessão do operador precisa valer no momento de CADA leitura (SessaoOperadorService.validar), a leitura
 * cadastrada vai para LeituraAndroidService (que publica na tela em tempo real e ignora reenvios) e a não
 * cadastrada para RfidService.
 */
@Service
class LeituraLoteService(
    private val rfidService: RfidService,
    private val leituraService: LeituraAndroidService,
    private val sessoes: SessaoOperadorService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun processar(itens: List<ItemLote>, token: String, dispositivo: String, operador: String?): List<ResultadoItemLote> {
        val momentosInvalidos = validarMomentos(itens, token, dispositivo)
        var repetidas = 0

        val resultados = itens.mapIndexed { indice, item ->
            val tag = item.tag.trim()

            momentosInvalidos[indice]?.let { motivo ->
                return@mapIndexed ResultadoItemLote(indice, tag, StatusItemLote.RECUSADA, mensagem = motivo)
            }
            if (tag.isEmpty() || tag.length > TAMANHO_MAXIMO_TAG) {
                val motivo = if (tag.isEmpty()) "Informe a TAG RFID" else "TAG RFID com mais de $TAMANHO_MAXIMO_TAG caracteres"
                return@mapIndexed ResultadoItemLote(indice, tag, StatusItemLote.RECUSADA, mensagem = motivo)
            }

            try {
                val dados = rfidService.buscarPorTag(tag)
                if (dados != null) {
                    if (leituraService.salvarLeitura(tag, item.lidoEm, operador) == ResultadoLeitura.REPETIDA) repetidas++
                    ResultadoItemLote(indice, tag, StatusItemLote.CADASTRADA, dados = dados)
                } else {
                    rfidService.salvarNaoCadastrada(tag)
                    ResultadoItemLote(indice, tag, StatusItemLote.NAO_CADASTRADA)
                }
            } catch (e: IllegalArgumentException) {
                ResultadoItemLote(indice, tag, StatusItemLote.RECUSADA, mensagem = e.message)
            } catch (e: Exception) {
                log.error("Erro ao processar a tag {} do lote do coletor {}", tag, dispositivo, e)
                ResultadoItemLote(indice, tag, StatusItemLote.ERRO, mensagem = "Erro no servidor ao processar a tag")
            }
        }

        registrarResumo(resultados, repetidas, dispositivo, operador)
        return resultados
    }

    /**
     * Índice → motivo, para os itens cuja hora de leitura está fora da sessão do operador. A validade da sessão
     * é um intervalo de tempo: se a leitura mais antiga e a mais nova do lote valem, todas valem (2 consultas
     * em vez de uma por item). Só quando uma das pontas falha confere item por item.
     */
    private fun validarMomentos(itens: List<ItemLote>, token: String, dispositivo: String): Map<Int, String> {
        if (itens.isEmpty()) return emptyMap()

        val momentos = itens.map { momentoDaLeitura(it.lidoEm) }
        val pontas = listOf(momentos.min(), momentos.max())

        if (pontas.all { motivoSeInvalido(token, dispositivo, it) == null }) return emptyMap()

        val invalidos = momentos.withIndex()
            .mapNotNull { (indice, momento) -> motivoSeInvalido(token, dispositivo, momento)?.let { indice to it } }
            .toMap()

        log.warn("Lote do coletor {}: {} de {} leitura(s) feitas fora da sessão do operador foram recusadas ({})",
            dispositivo, invalidos.size, itens.size, invalidos.values.distinct().joinToString("; "))
        return invalidos
    }

    private fun motivoSeInvalido(token: String, dispositivo: String, momento: LocalDateTime): String? =
        try {
            sessoes.validar(token, dispositivo, momento)
            null
        } catch (e: SessaoInvalidaException) {
            e.message ?: "Sessão do operador inválida"
        }

    // Mesma regra do X-Momento (OperadorFilter): sem hora, ou com relógio do coletor adiantado, vale agora
    private fun momentoDaLeitura(lidoEm: OffsetDateTime?): LocalDateTime {
        val agora = LocalDateTime.now(relogio)
        val lida = lidoEm?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime() ?: return agora
        return if (lida.isAfter(agora.plus(SessaoOperadorService.TOLERANCIA))) agora else lida
    }

    // Uma linha por lote (e não por tag): com o gatilho apertado, o coletor manda centenas de leituras
    private fun registrarResumo(resultados: List<ResultadoItemLote>, repetidas: Int, dispositivo: String, operador: String?) {
        val porStatus = resultados.groupingBy { it.status }.eachCount()
        val cadastradas = porStatus[StatusItemLote.CADASTRADA] ?: 0
        val recusadas = porStatus[StatusItemLote.RECUSADA] ?: 0
        val erros = porStatus[StatusItemLote.ERRO] ?: 0

        val resumo = buildString {
            append("$cadastradas cadastrada(s)")
            if (repetidas > 0) append(" (das quais $repetidas reenviada(s), já registrada(s))")
            append(", ${porStatus[StatusItemLote.NAO_CADASTRADA] ?: 0} não cadastrada(s)")
            if (recusadas > 0) append(", $recusadas recusada(s)")
            if (erros > 0) append(", $erros com erro")
        }

        if (recusadas > 0 || erros > 0) {
            log.warn("Lote de {} leitura(s) do coletor {} (operador {}): {}", resultados.size, dispositivo, operador ?: "—", resumo)
        } else {
            log.info("Lote de {} leitura(s) do coletor {} (operador {}): {}", resultados.size, dispositivo, operador ?: "—", resumo)
        }
    }

    companion object {
        /** Máximo de leituras por lote (o app manda 100): um app com fila enorme não trava o servidor. */
        const val TAMANHO_MAXIMO_LOTE = 500
        const val TAMANHO_MAXIMO_TAG = 255
    }
}
