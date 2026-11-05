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
    val mensagem: String? = null,
    /** Só nos RECUSADA por sessão: o mesmo motivo do 401 (MotivoSessaoInvalida). */
    val motivo: MotivoSessaoInvalida? = null
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

        // Tudo o que o lote precisa consultar, carregado de uma vez (antes: cerca de 6 consultas por leitura)
        val validas = itens.withIndex()
            .filter { (indice, item) -> indice !in momentosInvalidos && tagAceita(item.tag.trim()) }
            .map { it.value }
        val epcs = validas.map { it.tag.trim() }.toSet()
        val (cadastradas, nomes) = rfidService.carregarParaLote(epcs)
        // Banco com comparação sem diferenciar maiúsculas: "abc" cadastrada também conta para "ABC" (como antes)
        val cadastradasSemCaixa = cadastradas.keys.mapTo(HashSet()) { it.lowercase() }
        val pendentes = rfidService.jaPendentes(epcs - cadastradas.keys).toMutableSet()
        val gravadas = inicioDasLeituras(validas)
            ?.let { leituraService.gravadasDesde(cadastradas.keys, it) } ?: mutableSetOf()

        val resultados = itens.mapIndexed { indice, item ->
            val tag = item.tag.trim()

            momentosInvalidos[indice]?.let { recusa ->
                return@mapIndexed ResultadoItemLote(indice, tag, StatusItemLote.RECUSADA, mensagem = recusa.message, motivo = recusa.motivo)
            }
            if (!tagAceita(tag)) {
                val motivo = if (tag.isEmpty()) "Informe a TAG RFID" else "TAG RFID com mais de $TAMANHO_MAXIMO_TAG caracteres"
                return@mapIndexed ResultadoItemLote(indice, tag, StatusItemLote.RECUSADA, mensagem = motivo)
            }

            try {
                val cadastrada = cadastradas[tag]
                if (cadastrada != null) {
                    val resultado = leituraService.salvarLeituraDoLote(cadastrada, nomes, item.lidoEm, operador, gravadas)
                    if (resultado == ResultadoLeitura.REPETIDA) repetidas++
                    ResultadoItemLote(indice, tag, StatusItemLote.CADASTRADA, dados = rfidService.resposta(cadastrada, nomes))
                } else {
                    // A mesma tag pode vir várias vezes no lote: só a primeira grava a pendência
                    if (tag !in pendentes && tag.lowercase() !in cadastradasSemCaixa) {
                        rfidService.registrarPendenteDoLote(tag)
                        pendentes += tag
                    }
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
     * Índice → motivo, para os itens cuja hora de leitura está fora da sessão do operador. A sessão e o usuário são
     * carregados uma vez só para o lote inteiro (antes, se uma leitura caía fora da sessão, conferia item por item:
     * até 1.500 consultas num lote de 500). O que vale para o lote todo (sessão desconhecida, usuário desativado...)
     * recusa todos os itens com o mesmo motivo.
     */
    private fun validarMomentos(itens: List<ItemLote>, token: String, dispositivo: String): Map<Int, SessaoInvalidaException> {
        if (itens.isEmpty()) return emptyMap()

        val momentos = itens.map { momentoDaLeitura(it.lidoEm) }
        val invalidos = try {
            sessoes.conferirMomentos(token, dispositivo, momentos)
        } catch (e: SessaoInvalidaException) {
            itens.indices.associateWith { e }
        }
        if (invalidos.isEmpty()) return emptyMap()

        log.warn("Lote do coletor {}: {} de {} leitura(s) feitas fora da sessão do operador foram recusadas ({})",
            dispositivo, invalidos.size, itens.size, invalidos.values.map { it.message }.distinct().joinToString("; "))
        return invalidos
    }

    private fun tagAceita(tag: String) = tag.isNotEmpty() && tag.length <= TAMANHO_MAXIMO_TAG

    /**
     * A hora mais antiga entre as leituras com hora informada (só elas são conferidas como reenvio), no fuso do
     * servidor; null se nenhuma tem hora. É a mesma hora gravada em lido_em_coletor (mesmo no futuro), então serve.
     */
    private fun inicioDasLeituras(itens: List<ItemLote>): LocalDateTime? =
        itens.mapNotNull { it.lidoEm?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime() }.minOrNull()


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
