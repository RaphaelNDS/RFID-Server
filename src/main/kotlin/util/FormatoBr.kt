package org.example.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Valores em reais e datas no formato brasileiro, para as telas, os PDFs e a importação. */
object FormatoBr {
    private val DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** "R$ 1.234,56". */
    fun reais(valor: BigDecimal?): String {
        if (valor == null) return ""
        val f = NumberFormat.getNumberInstance(Locale("pt", "BR"))
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
            ?: runCatching { LocalDate.parse(t, DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT)) }.getOrNull()
            ?: throw IllegalArgumentException("Data inválida: $texto (use dd/mm/aaaa)")
    }
}
