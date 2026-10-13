package org.example.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class TentativasLoginServiceTest {

    /** Relógio que o teste avança na mão. */
    private class RelogioDeTeste(var agora: Instant = Instant.parse("2026-10-13T10:00:00Z")) : Clock() {
        override fun instant() = agora
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?) = this
        fun avancar(minutos: Long) { agora = agora.plus(Duration.ofMinutes(minutos)) }
    }

    private val relogio = RelogioDeTeste()
    private val service = TentativasLoginService(maxTentativas = 5, maxTentativasIp = 20, bloqueioMinutos = 15, relogio = relogio)

    private fun errar(email: String, vezes: Int, ip: String = "10.0.0.1") = repeat(vezes) { service.registrarFalha(email, ip) }

    @Test
    fun `quatro erros ainda nao bloqueiam`() {
        errar("ana@x.com", 4)

        assertThat(service.tempoRestanteDeBloqueio("ana@x.com", null)).isNull()
    }

    @Test
    fun `quinto erro bloqueia o email por 15 minutos`() {
        errar("ana@x.com", 5)

        assertThat(service.tempoRestanteDeBloqueio("ana@x.com", null)).isEqualTo(Duration.ofMinutes(15))
    }

    @Test
    fun `bloqueio nao depende de maiusculas nem espacos`() {
        errar("Ana@X.com ", 5)

        assertThat(service.tempoRestanteDeBloqueio("ana@x.com", null)).isNotNull()
    }

    @Test
    fun `bloqueio de um email nao afeta outro`() {
        errar("ana@x.com", 5)

        assertThat(service.tempoRestanteDeBloqueio("bruno@x.com", "10.0.0.2")).isNull()
    }

    @Test
    fun `bloqueio termina sozinho depois do tempo`() {
        errar("ana@x.com", 5)

        relogio.avancar(14)
        assertThat(service.tempoRestanteDeBloqueio("ana@x.com", null)).isEqualTo(Duration.ofMinutes(1))

        relogio.avancar(1)
        assertThat(service.tempoRestanteDeBloqueio("ana@x.com", null)).isNull()
    }

    @Test
    fun `erros antigos fora da janela nao contam`() {
        errar("ana@x.com", 4)
        relogio.avancar(16)
        errar("ana@x.com", 1)

        assertThat(service.tempoRestanteDeBloqueio("ana@x.com", null)).isNull()
    }

    @Test
    fun `login certo zera o contador do email`() {
        errar("ana@x.com", 4)
        service.registrarSucesso("ana@x.com")
        errar("ana@x.com", 4)

        assertThat(service.tempoRestanteDeBloqueio("ana@x.com", null)).isNull()
    }

    @Test
    fun `depois do bloqueio vencer o usuario tem novamente 5 tentativas`() {
        errar("ana@x.com", 5)
        relogio.avancar(15)

        errar("ana@x.com", 4)
        assertThat(service.tempoRestanteDeBloqueio("ana@x.com", null)).isNull()

        errar("ana@x.com", 1)
        assertThat(service.tempoRestanteDeBloqueio("ana@x.com", null)).isNotNull()
    }

    @Test
    fun `IP que testa senhas em varias contas e bloqueado`() {
        // 20 contas diferentes, 1 erro cada: nenhuma conta chega a 5, mas o IP chega a 20
        (1..20).forEach { service.registrarFalha("conta$it@x.com", "200.1.1.1") }

        assertThat(service.tempoRestanteDeBloqueio("conta-nova@x.com", "200.1.1.1")).isEqualTo(Duration.ofMinutes(15))
        assertThat(service.tempoRestanteDeBloqueio("conta-nova@x.com", "200.1.1.2")).isNull()
    }

    @Test
    fun `sucesso nao zera o contador do IP`() {
        // Senão um atacante com uma conta válida "limparia" o IP entre as tentativas nas outras contas
        (1..19).forEach { service.registrarFalha("conta$it@x.com", "200.1.1.1") }
        service.registrarSucesso("minha-conta@x.com")
        service.registrarFalha("conta20@x.com", "200.1.1.1")

        assertThat(service.tempoRestanteDeBloqueio(null, "200.1.1.1")).isNotNull()
    }

    @Test
    fun `limpeza remove registros vencidos sem desbloquear quem ainda esta bloqueado`() {
        errar("velho@x.com", 2, ip = "10.0.0.9")
        errar("bloqueado@x.com", 5, ip = "10.0.0.8")
        relogio.avancar(10)

        service.limparExpirados()

        assertThat(service.tempoRestanteDeBloqueio("bloqueado@x.com", null)).isEqualTo(Duration.ofMinutes(5))
    }
}
