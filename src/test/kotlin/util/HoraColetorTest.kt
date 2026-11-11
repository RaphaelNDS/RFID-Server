package org.example.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A regra única da hora do coletor (1.5.2). */
class HoraColetorTest {

    private val agora = LocalDateTime.of(2026, 11, 9, 13, 0)
    private fun iso(local: LocalDateTime) = local.atZone(ZoneId.systemDefault()).toOffsetDateTime()

    @Test
    fun `hora passada vale no fuso do servidor e sem hora vale null`() {
        assertThat(HoraColetor.aceita(iso(agora.minusHours(30)), agora)).isEqualTo(agora.minusHours(30))
        assertThat(HoraColetor.aceita(null, agora)).isNull()
        // Outro fuso: a mesma hora, convertida
        val emUtc = iso(agora.minusMinutes(10)).withOffsetSameInstant(ZoneOffset.UTC)
        assertThat(HoraColetor.aceita(emUtc, agora)).isEqualTo(agora.minusMinutes(10))
    }

    @Test
    fun `ate 5 min no futuro vale e acima disso nao`() {
        assertThat(HoraColetor.aceita(iso(agora.plusMinutes(4)), agora)).isEqualTo(agora.plusMinutes(4))
        assertThat(HoraColetor.aceita(iso(agora.plusMinutes(5)), agora)).isEqualTo(agora.plusMinutes(5))
        assertThat(HoraColetor.aceita(iso(agora.plusMinutes(5).plusSeconds(1)), agora)).isNull()
        assertThat(HoraColetor.noFuturo(agora.plusMinutes(6), agora)).isTrue()
    }
}
