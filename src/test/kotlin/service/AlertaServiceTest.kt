package org.example.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Avisos por email (1.5.1) e o monitor de fora do ar. */
class AlertaServiceTest {

    private val zona = ZoneId.systemDefault()
    private var agora: Instant = LocalDateTime.of(2026, 11, 9, 13, 0).atZone(zona).toInstant()
    private val relogio = object : Clock() {
        override fun getZone() = zona
        override fun withZone(z: ZoneId?) = this
        override fun instant() = agora
    }

    private val enviador = mockk<JavaMailSender>(relaxed = true)
    private val provedor = mockk<ObjectProvider<JavaMailSender>> {
        every { ifAvailable } returns enviador
        every { getObject() } returns enviador
    }

    private fun alertas(host: String = "smtp.empresa.com", para: String = "ti@empresa.com, chefe@empresa.com") =
        AlertaService(provedor, host, "rfid@empresa.com", para, "", 24, relogio)

    @Test
    fun `problema avisa uma vez e repete so depois de 24 horas e o resolvido avisa uma vez`() {
        val a = alertas()
        a.problema("backup-banco", "Backup do banco com problema", "atrasado")
        a.problema("backup-banco", "Backup do banco com problema", "atrasado")
        verify(timeout = 3000, exactly = 1) { enviador.send(any<SimpleMailMessage>()) }

        agora = agora.plusSeconds(25 * 3600)
        a.problema("backup-banco", "Backup do banco com problema", "atrasado")
        verify(timeout = 3000, exactly = 2) { enviador.send(any<SimpleMailMessage>()) }

        a.resolvido("backup-banco", "Backup do banco", "voltou")
        a.resolvido("backup-banco", "Backup do banco", "voltou")   // já resolvido: não repete
        verify(timeout = 3000, exactly = 3) { enviador.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `email vai para todos os destinatarios com o assunto marcado`() {
        val enviadas = java.util.concurrent.CopyOnWriteArrayList<SimpleMailMessage>()
        every { enviador.send(any<SimpleMailMessage>()) } answers { enviadas += firstArg<SimpleMailMessage>() }

        alertas().informar("Servidor ficou fora do ar por 2h 0min", "texto")

        verify(timeout = 3000) { enviador.send(any<SimpleMailMessage>()) }
        val mensagem = enviadas.single()
        assertThat(mensagem.to).containsExactly("ti@empresa.com", "chefe@empresa.com")
        assertThat(mensagem.from).isEqualTo("rfid@empresa.com")
        assertThat(mensagem.subject).isEqualTo("[Servidor RFID] Servidor ficou fora do ar por 2h 0min")
    }

    @Test
    fun `sem SMTP ou sem destinatario fica desligado e nao envia`() {
        val semHost = alertas(host = "")
        semHost.problema("x", "a", "b")
        assertThat(semHost.ligado).isFalse()
        assertThat(semHost.situacao()).startsWith("desligados: defina o servidor de email")
        assertThat(semHost.enviarTeste("ana").isFailure).isTrue()

        assertThat(alertas(para = "nao-e-email").ligado).isFalse()
        Thread.sleep(200)
        verify(exactly = 0) { enviador.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `falha do SMTP aparece na situacao e o teste mostra o erro`() {
        every { enviador.send(any<SimpleMailMessage>()) } throws MailSendException("535 Authentication unsuccessful")
        val a = alertas()

        val teste = a.enviarTeste("ana@empresa.com")

        assertThat(teste.exceptionOrNull()!!.message).contains("535")
        assertThat(a.comProblema).isTrue()
        assertThat(a.situacao()).contains("o último envio FALHOU").contains("535")
    }

    // ---------- Monitor: fora do ar ----------

    @TempDir lateinit var pasta: Path

    private fun monitor(alertas: AlertaService, arquivo: Path) = MonitorServidorService(
        arquivo.toString(), 30, alertas,
        BackupBancoService(pasta.resolve("sem-backups").toString(), 12, relogio),
        CopiaExternaBancoService("", 14, 24, "", BackupBancoService(pasta.toString(), 12, relogio), relogio), relogio)

    @Test
    fun `ao subir depois de mais de 30 min fora do ar avisa de quando a quando`() {
        val arquivo = pasta.resolve("servidor-no-ar.txt")
        Files.writeString(arquivo, LocalDateTime.ofInstant(agora, zona).minusHours(2).toString())
        val a = mockk<AlertaService>(relaxed = true)

        monitor(a, arquivo).aoSubir()

        verify { a.informar("Servidor ficou fora do ar por 2h 0min", match { it.contains("09/11/2026 11:00 até 09/11/2026 13:00") }) }
        assertThat(Files.readString(arquivo)).isEqualTo(LocalDateTime.ofInstant(agora, zona).toString())
    }

    @Test
    fun `reinicio rapido ou primeira vez nao avisam`() {
        val arquivo = pasta.resolve("servidor-no-ar.txt")
        val a = mockk<AlertaService>(relaxed = true)
        monitor(a, arquivo).aoSubir()   // primeira vez: sem arquivo
        agora = agora.plusSeconds(60)
        monitor(a, arquivo).aoSubir()   // troca de versão: 1 min fora

        verify(exactly = 0) { a.informar(any(), any()) }
    }

    @Test
    fun `buraco entre duas gravacoes com o servidor no ar e computador suspenso`() {
        val arquivo = pasta.resolve("servidor-no-ar.txt")
        val a = mockk<AlertaService>(relaxed = true)
        val m = monitor(a, arquivo)
        m.aoSubir()
        agora = agora.plusSeconds(3 * 3600)

        m.registrarNoAr()

        verify { a.informar("Servidor ficou parado por 3h 0min", match { it.contains("suspendeu") }) }
    }
}
