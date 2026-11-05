package org.example.config

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.junit.jupiter.api.Test
import java.time.Duration

class BackupAntesMigracaoTest {

    // O próprio java desta JVM: existe em qualquer máquina (Windows e o Linux do CI)
    private val java = ProcessHandle.current().info().command().orElse("java")

    @Test
    fun `comando que termina bem nao lanca`() {
        BackupAntesMigracao("$java -version").executar()
    }

    @Test
    fun `comando com codigo de erro impede a migracao e explica como resolver`() {
        assertThatThrownBy { BackupAntesMigracao("$java -cp nada ClasseQueNaoExiste").executar() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("terminou com código 1")
            .hasMessageContaining("A migração NÃO foi aplicada")
            .hasMessageContaining("RFID_BACKUP_ANTES_MIGRACAO")
    }

    @Test
    fun `programa que nao existe impede a migracao`() {
        assertThatThrownBy { BackupAntesMigracao("programa-que-nao-existe-123 /backup.sh").executar() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("não foi possível executar o comando")
    }

    @Test
    fun `comando que demora alem do limite e interrompido`() {
        // Limite zero: nem uma JVM com -version termina a tempo
        assertThatThrownBy { BackupAntesMigracao("$java -version", limite = Duration.ZERO).executar() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("sem terminar")
    }

    @Test
    fun `comando vazio desliga o backup`() {
        val backup = BackupAntesMigracao("   ")
        assertThat(backup.ligado).isFalse()
        backup.executar()
    }

    @Test
    fun `comando e separado pelos espacos`() {
        assertThat(BackupAntesMigracao("  docker   exec rfid-backup /backup.sh ").partes)
            .containsExactly("docker", "exec", "rfid-backup", "/backup.sh")
    }

    // ---------------- estratégia de migração ----------------

    private val backup = mockk<BackupAntesMigracao>(relaxed = true)
    private val estrategia = MigracoesConfig("").estrategia(backup)

    private fun flyway(pendentes: Int, versaoAtual: String?): Flyway {
        val flyway = mockk<Flyway>(relaxed = true)
        val atual = versaoAtual?.let { v -> mockk<MigrationInfo>(relaxed = true) { every { version.toString() } returns v } }
        every { flyway.info().pending() } returns Array(pendentes) { mockk(relaxed = true) }
        every { flyway.info().current() } returns atual
        return flyway
    }

    @Test
    fun `com migracao pendente faz o backup antes de migrar`() {
        val flyway = flyway(pendentes = 1, versaoAtual = "11")

        estrategia.migrate(flyway)

        verifyOrder {
            backup.executar()
            flyway.migrate()
        }
    }

    @Test
    fun `backup que falha nao deixa migrar`() {
        val flyway = flyway(pendentes = 2, versaoAtual = "11")
        every { backup.executar() } throws IllegalStateException("falhou")

        assertThatThrownBy { estrategia.migrate(flyway) }.hasMessage("falhou")
        verify(exactly = 0) { flyway.migrate() }
    }

    @Test
    fun `banco vazio migra sem backup`() {
        val flyway = flyway(pendentes = 11, versaoAtual = null)

        estrategia.migrate(flyway)

        verify(exactly = 0) { backup.executar() }
        verify { flyway.migrate() }
    }

    @Test
    fun `sem migracao pendente nao faz backup nem migra`() {
        val flyway = flyway(pendentes = 0, versaoAtual = "11")

        estrategia.migrate(flyway)

        verify(exactly = 0) { backup.executar() }
        verify(exactly = 0) { flyway.migrate() }
    }
}
