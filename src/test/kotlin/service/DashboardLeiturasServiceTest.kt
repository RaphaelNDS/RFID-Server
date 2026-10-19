package org.example.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.example.repository.LeituraAndroidRepository
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.sql.Date
import java.time.LocalDate

class DashboardLeiturasServiceTest {

    private val repo = mockk<LeituraAndroidRepository>()
    private val service = DashboardLeiturasService(repo)

    @Nested
    inner class Periodo {

        private val hoje = LocalDate.of(2026, 10, 13)

        @Test
        fun `sem filtro usa os ultimos 7 dias incluindo hoje`() {
            val p = service.resolverPeriodo(null, null, hoje)

            assertThat(p.inicio).isEqualTo(LocalDate.of(2026, 10, 7))
            assertThat(p.fim).isEqualTo(hoje)
            assertThat(p.aviso).isNull()
        }

        @Test
        fun `campos em branco contam como sem filtro`() {
            assertThat(service.resolverPeriodo("", " ", hoje).inicio).isEqualTo(LocalDate.of(2026, 10, 7))
        }

        @Test
        fun `periodo informado e respeitado`() {
            val p = service.resolverPeriodo("2026-10-01", "2026-10-03", hoje)

            assertThat(p.inicio).isEqualTo(LocalDate.of(2026, 10, 1))
            assertThat(p.fim).isEqualTo(LocalDate.of(2026, 10, 3))
        }

        @Test
        fun `so o fim informado completa o inicio com 7 dias antes`() {
            val p = service.resolverPeriodo(null, "2026-10-10", hoje)

            assertThat(p.inicio).isEqualTo(LocalDate.of(2026, 10, 4))
            assertThat(p.fim).isEqualTo(LocalDate.of(2026, 10, 10))
        }

        @Test
        fun `so o inicio informado vai ate hoje`() {
            val p = service.resolverPeriodo("2026-10-01", null, hoje)

            assertThat(p.fim).isEqualTo(hoje)
        }

        @Test
        fun `data invalida volta ao padrao com aviso (antes dava erro 500)`() {
            val p = service.resolverPeriodo("abc", null, hoje)

            assertThat(p.inicio).isEqualTo(LocalDate.of(2026, 10, 7))
            assertThat(p.aviso).contains("Data inválida")
        }

        @Test
        fun `inicio depois do fim volta ao padrao com aviso`() {
            val p = service.resolverPeriodo("2026-10-13", "2026-10-01", hoje)

            assertThat(p.fim).isEqualTo(hoje)
            assertThat(p.aviso).contains("depois da data de fim")
        }
    }

    @Nested
    inner class Calculo {

        // Período no passado, para não se misturar com as contagens de "hoje" e "ontem"
        private val periodo = PeriodoDashboard(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 4))
        private val de = periodo.inicio.atStartOfDay()
        private val ate = periodo.fim.plusDays(1).atStartOfDay()

        private fun configurar(hojeQtd: Long, ontemQtd: Long) {
            val hoje = LocalDate.now()
            every { repo.contarEntre(hoje.atStartOfDay(), hoje.plusDays(1).atStartOfDay()) } returns hojeQtd
            every { repo.contarEntre(hoje.minusDays(1).atStartOfDay(), hoje.atStartOfDay()) } returns ontemQtd
            every { repo.contarEntre(de, ate) } returns 7
            every { repo.contarDesde(any()) } returns 2
            every { repo.contarPorDiaEntre(de, ate) } returns listOf(
                arrayOf(Date.valueOf("2020-01-01"), 3L),
                arrayOf(Date.valueOf("2020-01-03"), 4L)
            )
            every { repo.contarPorTipoEntre(de, ate) } returns listOf(arrayOf("Notebook", 5L), arrayOf(null, 2L))
            every { repo.contarPorMarcaEntre(de, ate) } returns listOf(arrayOf("Dell", 6L), arrayOf("", 1L))
        }

        @Test
        fun `consulta o periodo como intervalo de meia-noite a meia-noite seguinte`() {
            configurar(10, 5)

            val dados = service.calcular(periodo)

            assertThat(dados.totalPeriodo).isEqualTo(7)
            assertThat(dados.leiturasHoje).isEqualTo(10)
            assertThat(dados.leiturasUltimaHora).isEqualTo(2)
        }

        @Test
        fun `grafico diario inclui os dias sem leitura com zero`() {
            configurar(0, 0)

            val porDia = service.calcular(periodo).porDia

            assertThat(porDia).containsExactly(
                org.assertj.core.api.Assertions.entry("2020-01-01", 3L),
                org.assertj.core.api.Assertions.entry("2020-01-02", 0L),
                org.assertj.core.api.Assertions.entry("2020-01-03", 4L),
                org.assertj.core.api.Assertions.entry("2020-01-04", 0L)
            )
        }

        @Test
        fun `tipo e marca vazios ganham rotulo em vez de 'null'`() {
            configurar(0, 0)

            val dados = service.calcular(periodo)

            assertThat(dados.porTipo).containsEntry("Sem tipo", 2L).containsEntry("Notebook", 5L)
            assertThat(dados.porMarca).containsEntry("Sem marca", 1L)
        }

        @Test
        fun `crescimento positivo no formato brasileiro`() {
            configurar(hojeQtd = 15, ontemQtd = 10)

            assertThat(service.calcular(periodo).crescimento).isEqualTo("+50,00%")
        }

        @Test
        fun `crescimento negativo`() {
            configurar(hojeQtd = 3, ontemQtd = 5)

            assertThat(service.calcular(periodo).crescimento).isEqualTo("-40,00%")
        }

        @Test
        fun `sem leituras ontem mostra traco em vez de 0 por cento`() {
            configurar(hojeQtd = 8, ontemQtd = 0)

            assertThat(service.calcular(periodo).crescimento).isEqualTo("—")
        }
    }
}
