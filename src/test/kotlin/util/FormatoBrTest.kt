package org.example.util

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Leitura e formatação de valores e datas no formato brasileiro (era parte do DepreciacaoTest até a 1.18.1). */
class FormatoBrTest {

    private fun bd(s: String) = BigDecimal(s)
    private val jan15 = LocalDate.of(2026, 1, 15)

    @Test
    fun `le o valor como o brasileiro digita e como o Excel manda`() {
        assertThat(FormatoBr.lerValor("4.599,90")).isEqualByComparingTo("4599.90")
        assertThat(FormatoBr.lerValor("R$ 1.234.567,8")).isEqualByComparingTo("1234567.80")
        assertThat(FormatoBr.lerValor("1234,5")).isEqualByComparingTo("1234.50")
        assertThat(FormatoBr.lerValor("1234.5")).isEqualByComparingTo("1234.50")      // célula numérica do .xlsx
        assertThat(FormatoBr.lerValor("1.234")).isEqualByComparingTo("1234.00")       // milhar
        assertThat(FormatoBr.lerValor("999.99000000000001")).isEqualByComparingTo("999.99")
        assertThat(FormatoBr.lerValor("  ")).isNull()
        assertThat(FormatoBr.lerValor(null)).isNull()
        assertThatThrownBy { FormatoBr.lerValor("-10") }.hasMessageContaining("negativo")
        assertThatThrownBy { FormatoBr.lerValor("dez reais") }.hasMessageContaining("inválido")
    }

    @Test
    fun `le a data do campo, da planilha e o numero de serie do Excel`() {
        assertThat(FormatoBr.lerData("2026-01-15")).isEqualTo(jan15)
        assertThat(FormatoBr.lerData("15/01/2026")).isEqualTo(jan15)
        assertThat(FormatoBr.lerData("5/1/2026")).isEqualTo(LocalDate.of(2026, 1, 5))
        assertThat(FormatoBr.lerData("46037")).isEqualTo(jan15)      // a célula de data do .xlsx
        assertThat(FormatoBr.lerData("")).isNull()
        assertThatThrownBy { FormatoBr.lerData("31/02/2026") }.hasMessageContaining("Data inválida")
    }

    @Test
    fun `formata em reais`() {
        assertThat(FormatoBr.reais(bd("1234567.8"))).isEqualTo("R$ 1.234.567,80")
        assertThat(FormatoBr.numero(bd("0"))).isEqualTo("0,00")
        assertThat(FormatoBr.reais(null)).isEmpty()
    }
}
