package org.example.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Depreciação linear de um bem numa data (1.8.0, CPC 27).
 *
 * - O valor residual é a % do tipo sobre o valor de aquisição; o que se deprecia é o valor menos o residual.
 * - Conta os meses COMPLETOS desde a data de aquisição até a data de referência (comprado em 15/01, deprecia o 1º mês
 *   em 15/02). Bem baixado para de depreciar na data da baixa.
 * - A acumulada é calculada sobre o total (depreciável × meses ÷ vida útil), sem somar a parcela arredondada mês a
 *   mês: no último mês fecha exatamente no residual.
 *
 * [semDepreciacao] diz por que o bem não deprecia (tipo sem vida útil, sem data de aquisição): o valor contábil é o
 * de aquisição.
 */
data class Depreciacao(
    val valorAquisicao: BigDecimal,
    val valorResidual: BigDecimal,
    val depreciacaoMensal: BigDecimal,
    val mesesDepreciados: Int,
    val vidaUtilMeses: Int?,
    val depreciacaoAcumulada: BigDecimal,
    val valorContabil: BigDecimal,
    val semDepreciacao: String? = null
) {
    val totalmenteDepreciada: Boolean get() = vidaUtilMeses != null && mesesDepreciados >= vidaUtilMeses

    companion object {
        private val CEM = BigDecimal(100)
        private val ZERO = BigDecimal.ZERO.setScale(2)

        /** Null quando o bem não tem valor de aquisição (fica fora dos totais). */
        fun calcular(
            valor: BigDecimal?,
            dataAquisicao: LocalDate?,
            vidaUtilMeses: Int?,
            residualPct: BigDecimal?,
            referencia: LocalDate,
            baixaEm: LocalDate? = null
        ): Depreciacao? {
            if (valor == null) return null
            val aquisicao = valor.setScale(2, RoundingMode.HALF_UP)
            val motivo = when {
                vidaUtilMeses == null || vidaUtilMeses <= 0 -> "o tipo de equipamento não tem vida útil"
                dataAquisicao == null -> "sem data de aquisição"
                else -> null
            }
            if (motivo != null) return Depreciacao(aquisicao, ZERO, ZERO, 0, vidaUtilMeses, ZERO, aquisicao, motivo)

            val vida = vidaUtilMeses!!
            val residual = aquisicao.multiply(residualPct ?: BigDecimal.ZERO).divide(CEM, 2, RoundingMode.HALF_UP)
            val depreciavel = aquisicao.subtract(residual).max(BigDecimal.ZERO)
            val fim = listOfNotNull(referencia, baixaEm).min()
            val meses = ChronoUnit.MONTHS.between(dataAquisicao, fim).coerceIn(0, vida.toLong()).toInt()
            val mensal = depreciavel.divide(BigDecimal(vida), 2, RoundingMode.HALF_UP)
            val acumulada = if (meses >= vida) depreciavel
                else depreciavel.multiply(BigDecimal(meses)).divide(BigDecimal(vida), 2, RoundingMode.HALF_UP)
            return Depreciacao(aquisicao, residual, mensal, meses, vida, acumulada, aquisicao.subtract(acumulada))
        }
    }
}

/** Valores em reais e datas no formato brasileiro, para as telas, os PDFs e a importação. */
object FormatoBr {
    private val DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** "R$ 1.234,56". */
    fun reais(valor: BigDecimal?): String {
        if (valor == null) return ""
        val f = java.text.NumberFormat.getNumberInstance(java.util.Locale("pt", "BR"))
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        return "R$ " + f.format(valor)
    }

    /** "1.234,56" (sem o R$, para o campo do formulário). */
    fun numero(valor: BigDecimal?): String = reais(valor).removePrefix("R$ ")

    fun data(d: LocalDate?): String = d?.format(DATA).orEmpty()

    /**
     * Valor digitado ou vindo da planilha: "1.234,56", "1234,56", "1234.56", "R$ 1.234" ou "1234". Ponto seguido de
     * exatamente 3 dígitos, sem vírgula, é separador de milhar ("1.234" = mil duzentos e trinta e quatro). Vazio = null.
     */
    fun lerValor(texto: String?): BigDecimal? {
        val limpo = texto?.replace("R$", "", ignoreCase = true)?.replace(Regex("[\\s\\u00A0]"), "").orEmpty()
        if (limpo.isEmpty()) return null
        val normal = when {
            limpo.contains(',') -> limpo.replace(".", "").replace(',', '.')
            limpo.count { it == '.' } > 1 || Regex("^\\d{1,3}\\.\\d{3}$").matches(limpo) -> limpo.replace(".", "")
            else -> limpo
        }
        val valor = normal.toBigDecimalOrNull() ?: throw IllegalArgumentException("Valor inválido: $texto (use 1.234,56)")
        require(valor.signum() >= 0) { "O valor não pode ser negativo: $texto" }
        require(valor.setScale(2, RoundingMode.HALF_UP).precision() <= 14) { "Valor grande demais: $texto" }
        return valor.setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * "2026-01-15" (campo de data), "15/01/2026" (planilha) ou o número de série do Excel (a célula de data do .xlsx
     * chega como "46037": dias desde 30/12/1899). Vazio = null.
     */
    fun lerData(texto: String?): LocalDate? {
        val t = texto?.trim().orEmpty()
        if (t.isEmpty()) return null
        Regex("^(\\d{5})(\\.\\d+)?$").matchEntire(t)?.let { m ->
            val dias = m.groupValues[1].toLong()
            if (dias in 20_000..80_000) return LocalDate.of(1899, 12, 30).plusDays(dias)
        }
        return runCatching { LocalDate.parse(t) }.getOrNull()
            ?: runCatching { LocalDate.parse(t, DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)) }.getOrNull()
            ?: throw IllegalArgumentException("Data inválida: $texto (use dd/mm/aaaa)")
    }
}
