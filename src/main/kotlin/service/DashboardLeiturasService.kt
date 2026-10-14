package org.example.service

import org.example.repository.LeituraAndroidRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

data class PeriodoDashboard(
    val inicio: LocalDate,
    val fim: LocalDate,
    /** Mensagem para o usuário quando o filtro informado foi descartado. */
    val aviso: String? = null
)

data class DadosDashboard(
    val periodo: PeriodoDashboard,
    val totalPeriodo: Long,
    val leiturasHoje: Long,
    val leiturasUltimaHora: Long,
    /** Variação de hoje em relação a ontem, já formatada (ex.: "+12,50%"); "—" quando ontem não teve leituras. */
    val crescimento: String,
    val porDia: Map<String, Long>,
    val porTipo: Map<String, Long>,
    val porMarca: Map<String, Long>
)

@Service
class DashboardLeiturasService(
    private val leituraRepo: LeituraAndroidRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Interpreta os filtros da tela. Sem filtro: últimos 7 dias (incluindo hoje).
     * Filtro inválido não derruba a página: volta ao padrão e devolve um aviso.
     */
    fun resolverPeriodo(inicio: String?, fim: String?, hoje: LocalDate = LocalDate.now()): PeriodoDashboard {
        val padrao = PeriodoDashboard(hoje.minusDays(DIAS_PADRAO - 1), hoje)

        val dataInicio: LocalDate?
        val dataFim: LocalDate?
        try {
            dataInicio = inicio?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            dataFim = fim?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        } catch (e: DateTimeParseException) {
            log.warn("Filtro de data inválido no dashboard (início='{}', fim='{}'); usando os últimos {} dias", inicio, fim, DIAS_PADRAO)
            return padrao.copy(aviso = "Data inválida no filtro. Mostrando os últimos $DIAS_PADRAO dias.")
        }

        // Só uma das pontas informada: completa com o padrão
        val periodoInicio = dataInicio ?: (dataFim ?: hoje).minusDays(DIAS_PADRAO - 1)
        val periodoFim = dataFim ?: hoje

        if (periodoInicio.isAfter(periodoFim)) {
            log.warn("Filtro do dashboard com início ({}) depois do fim ({}); usando os últimos {} dias", periodoInicio, periodoFim, DIAS_PADRAO)
            return padrao.copy(aviso = "A data de início é depois da data de fim. Mostrando os últimos $DIAS_PADRAO dias.")
        }

        return PeriodoDashboard(periodoInicio, periodoFim)
    }

    fun calcular(periodo: PeriodoDashboard): DadosDashboard {
        val inicioMs = System.currentTimeMillis()

        val agora = LocalDateTime.now()
        val hoje = agora.toLocalDate()

        // Intervalo semiaberto: do início do primeiro dia até a meia-noite seguinte ao último
        val de = periodo.inicio.atStartOfDay()
        val ate = periodo.fim.plusDays(1).atStartOfDay()

        val leiturasHoje = leituraRepo.contarEntre(hoje.atStartOfDay(), hoje.plusDays(1).atStartOfDay())
        val leiturasOntem = leituraRepo.contarEntre(hoje.minusDays(1).atStartOfDay(), hoje.atStartOfDay())

        val dados = DadosDashboard(
            periodo = periodo,
            totalPeriodo = leituraRepo.contarEntre(de, ate),
            leiturasHoje = leiturasHoje,
            leiturasUltimaHora = leituraRepo.contarDesde(agora.minusHours(1)),
            crescimento = formatarCrescimento(leiturasHoje, leiturasOntem),
            porDia = porDiaComZeros(periodo, leituraRepo.contarPorDiaEntre(de, ate)),
            porTipo = agrupado(leituraRepo.contarPorTipoEntre(de, ate), "Sem tipo"),
            porMarca = agrupado(leituraRepo.contarPorMarcaEntre(de, ate), "Sem marca")
        )

        val duracaoMs = System.currentTimeMillis() - inicioMs
        if (duracaoMs > LIMITE_LENTO_MS) {
            log.warn("Dashboard demorou {} ms para calcular (período {} a {}, {} leituras)", duracaoMs, periodo.inicio, periodo.fim, dados.totalPeriodo)
        } else {
            log.debug("Dashboard calculado em {} ms (período {} a {}, {} leituras)", duracaoMs, periodo.inicio, periodo.fim, dados.totalPeriodo)
        }

        return dados
    }

    // O gráfico de linha precisa de todos os dias do período, inclusive os que não tiveram leitura
    private fun porDiaComZeros(periodo: PeriodoDashboard, linhas: List<Array<Any>>): Map<String, Long> {
        val contagens = linhas.associate { LocalDate.parse(it[0].toString()) to (it[1] as Number).toLong() }

        return generateSequence(periodo.inicio) { it.plusDays(1) }
            .takeWhile { !it.isAfter(periodo.fim) }
            .associate { it.toString() to (contagens[it] ?: 0L) }
    }

    private fun agrupado(linhas: List<Array<Any?>>, rotuloVazio: String): Map<String, Long> =
        linhas.associate { (it[0]?.toString()?.takeIf(String::isNotBlank) ?: rotuloVazio) to (it[1] as Number).toLong() }

    private fun formatarCrescimento(hoje: Long, ontem: Long): String {
        if (ontem == 0L) return "—"
        val percentual = (hoje - ontem) * 100.0 / ontem
        return String.format(LOCALE_BR, "%+.2f%%", percentual)
    }

    companion object {
        const val DIAS_PADRAO = 7L
        private const val LIMITE_LENTO_MS = 1000L
        private val LOCALE_BR = java.util.Locale.forLanguageTag("pt-BR")
    }
}
