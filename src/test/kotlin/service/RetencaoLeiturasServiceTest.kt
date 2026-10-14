package org.example.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.example.repository.LeituraAndroidRepository
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RetencaoLeiturasServiceTest {

    private val repo = mockk<LeituraAndroidRepository>()
    private val relogio = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneId.of("UTC"))

    private fun service(dias: Long) = RetencaoLeiturasService(repo, relogio, dias)

    @Test
    fun `desligada por padrao nao apaga nada`() {
        val s = service(0)

        assertThat(s.ativa).isFalse()
        assertThat(s.apagarAntigas()).isZero()
        s.executarAgendada()

        verify(exactly = 0) { repo.apagarAnterioresA(any()) }
    }

    @Test
    fun `valor negativo tambem conta como desligada`() {
        assertThat(service(-5).ativa).isFalse()
    }

    @Test
    fun `apaga o que for anterior ao inicio do dia limite`() {
        every { repo.apagarAnterioresA(any()) } returns 42

        val apagadas = service(90).apagarAntigas()

        assertThat(apagadas).isEqualTo(42)
        verify { repo.apagarAnterioresA(LocalDate.of(2026, 7, 15).atStartOfDay()) } // 13/10 - 90 dias
    }

    @Test
    fun `prazo menor que 30 dias vira 30 (leituras offline chegam atrasadas)`() {
        every { repo.apagarAnterioresA(any()) } returns 0

        val s = service(3)

        assertThat(s.reterDias).isEqualTo(RetencaoLeiturasService.MINIMO_DIAS)
        s.apagarAntigas()
        verify { repo.apagarAnterioresA(LocalDate.of(2026, 9, 13).atStartOfDay()) }
    }

    @Test
    fun `falha na execucao agendada nao propaga`() {
        every { repo.apagarAnterioresA(any()) } throws RuntimeException("banco fora")

        service(90).executarAgendada() // não pode lançar
    }
}
