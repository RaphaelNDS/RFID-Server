package org.example.util

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** 1.8.0: depreciação linear e a leitura de valores e datas no formato brasileiro. */
class DepreciacaoTest {

    private fun bd(s: String) = BigDecimal(s)
    private val jan15 = LocalDate.of(2026, 1, 15)

    @Test
    fun `deprecia pelos meses completos desde a aquisicao, descontando o residual`() {
        // R$ 6.000, 60 meses, residual 10%: depreciável 5.400, 90 por mês
        val d = Depreciacao.calcular(bd("6000"), jan15, 60, bd("10"), LocalDate.of(2026, 7, 14))!!
        assertThat(d.valorResidual).isEqualByComparingTo("600.00")
        assertThat(d.depreciacaoMensal).isEqualByComparingTo("90.00")
        assertThat(d.mesesDepreciados).isEqualTo(5)          // 14/07 ainda não completou o 6º mês
        assertThat(d.depreciacaoAcumulada).isEqualByComparingTo("450.00")
        assertThat(d.valorContabil).isEqualByComparingTo("5550.00")
        assertThat(d.totalmenteDepreciada).isFalse()

        val noDia = Depreciacao.calcular(bd("6000"), jan15, 60, bd("10"), LocalDate.of(2026, 7, 15))!!
        assertThat(noDia.mesesDepreciados).isEqualTo(6)
    }

    @Test
    fun `fecha exatamente no residual no fim da vida util e nao passa dele`() {
        // 1.000 em 3 meses: 333,33 por mês, mas a acumulada fecha em 1.000 (sem sobra de arredondamento)
        val fim = Depreciacao.calcular(bd("1000"), jan15, 3, null, LocalDate.of(2030, 1, 1))!!
        assertThat(fim.mesesDepreciados).isEqualTo(3)
        assertThat(fim.depreciacaoAcumulada).isEqualByComparingTo("1000.00")
        assertThat(fim.valorContabil).isEqualByComparingTo("0.00")
        assertThat(fim.totalmenteDepreciada).isTrue()

        val doisMeses = Depreciacao.calcular(bd("1000"), jan15, 3, null, LocalDate.of(2026, 3, 15))!!
        assertThat(doisMeses.depreciacaoAcumulada).isEqualByComparingTo("666.67")
    }

    @Test
    fun `bem baixado para de depreciar na data da baixa`() {
        val d = Depreciacao.calcular(bd("1200"), jan15, 12, null, LocalDate.of(2027, 6, 1), baixaEm = LocalDate.of(2026, 4, 20))!!
        assertThat(d.mesesDepreciados).isEqualTo(3)
        assertThat(d.valorContabil).isEqualByComparingTo("900.00")
    }

    @Test
    fun `sem vida util ou sem data o bem fica pelo valor de aquisicao, e sem valor nao entra`() {
        val semVida = Depreciacao.calcular(bd("500"), jan15, null, null, LocalDate.of(2027, 1, 1))!!
        assertThat(semVida.valorContabil).isEqualByComparingTo("500.00")
        assertThat(semVida.semDepreciacao).contains("vida útil")

        val semData = Depreciacao.calcular(bd("500"), null, 60, null, LocalDate.of(2027, 1, 1))!!
        assertThat(semData.valorContabil).isEqualByComparingTo("500.00")
        assertThat(semData.semDepreciacao).contains("data de aquisição")

        assertThat(Depreciacao.calcular(null, jan15, 60, null, LocalDate.of(2027, 1, 1))).isNull()

        // Comprado depois da data de referência: nada depreciado ainda
        val futuro = Depreciacao.calcular(bd("500"), jan15, 60, null, LocalDate.of(2025, 12, 1))!!
        assertThat(futuro.mesesDepreciados).isZero()
        assertThat(futuro.valorContabil).isEqualByComparingTo("500.00")
    }

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
