package org.example.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.example.config.BancoDadosConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import java.util.Properties

class StatusServiceTest {

    private val bancoDados = mockk<BancoDadosConfig> { every { descricao } returns "H2 (arquivo)" }
    private val backup = mockk<BackupService> { every { resumoUltimoBackup() } returns "13/10/2026 02:00 (há 9h)" }

    private fun provider(props: BuildProperties?) = mockk<ObjectProvider<BuildProperties>> {
        every { ifAvailable } returns props
    }

    private fun build(versao: String) = BuildProperties(Properties().apply { setProperty("version", versao) })

    @Test
    fun `mostra a versao do build e o banco em uso`() {
        val status = StatusService(bancoDados, backup, provider(build("2.0.0"))).getStatus()

        assertThat(status.status).isEqualTo("ONLINE")
        assertThat(status.versao).isEqualTo("2.0.0")
        assertThat(status.banco).isEqualTo("H2 (arquivo)")
        assertThat(status.ultimoBackup).isEqualTo("13/10/2026 02:00 (há 9h)")
    }

    @Test
    fun `sem build-info a versao aparece como desenvolvimento`() {
        assertThat(StatusService(bancoDados, backup, provider(null)).getStatus().versao).isEqualTo("desenvolvimento")
    }

    @Test
    fun `hora de inicio e fixa (antes mostrava a hora atual a cada acesso)`() {
        val service = StatusService(bancoDados, backup, provider(null))

        val primeira = service.getStatus().horaServidor
        Thread.sleep(1100)
        val segunda = service.getStatus().horaServidor

        assertThat(primeira).startsWith("Desde ").isEqualTo(segunda)
    }

    @Test
    fun `tempo no ar vem preenchido`() {
        assertThat(StatusService(bancoDados, backup, provider(null)).getStatus().tempoOnline)
            .matches("menos de 1min|\\d+min|\\d+h \\d+min|\\d+d \\d+h \\d+min")
    }
}
