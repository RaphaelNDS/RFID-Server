package org.example.config

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.example.service.BackupBancoService
import org.example.service.CopiaExternaBancoService
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.nio.file.Path
import java.time.Clock

/** 1.15.0: sem a variável do OneDrive, o servidor sobe e só o backup que depende dela fica de fora. */
class VariaveisAusentesTest {

    private fun ambiente(vararg variaveis: Pair<String, String>) = StandardEnvironment().apply {
        // Sem as variáveis de verdade desta máquina: só as do teste, mais a pasta do config de produção
        propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        propertySources.addFirst(MapPropertySource("teste", mapOf(
            "rfid.backup.pasta" to "\${OneDriveCommercial}/Backups RFID/certificado",
            "rfid.banco.copia-externa" to "\${OneDriveCommercial}/Backups RFID/banco"
        ) + variaveis.toMap()))
        VariaveisAusentes().postProcessEnvironment(this, SpringApplication())
    }

    @Test
    fun `variavel ausente vira marcador em vez de derrubar a subida`() {
        val pasta = ambiente().getProperty("rfid.backup.pasta")!!   // antes: IllegalArgumentException (placeholder)
        assertThat(VariaveisAusentes.pastaIndisponivel(pasta)).isEqualTo("OneDriveCommercial")
    }

    @Test
    fun `variavel presente fica como esta`() {
        val pasta = ambiente("OneDriveCommercial" to "C:\\Users\\x\\OneDrive - Empresa").getProperty("rfid.backup.pasta")!!
        assertThat(pasta).isEqualTo("C:\\Users\\x\\OneDrive - Empresa/Backups RFID/certificado")
        assertThat(VariaveisAusentes.pastaIndisponivel(pasta)).isNull()
        assertThat(VariaveisAusentes.pastaIndisponivel("")).isNull()
    }

    @Test
    fun `sem a variavel o certificado vai para data-backups e a copia do banco fica desligada com aviso`() {
        val env = ambiente()
        val copia = CopiaExternaBancoService(env.getProperty("rfid.banco.copia-externa")!!, 14, 24, "senha-longa-de-teste",
            mockk<BackupBancoService>(relaxed = true), Clock.systemDefaultZone())
        assertThat(copia.pasta).isNull()
        assertThat(copia.copiar()).isNull()
        val situacao = copia.situacao()
        assertThat(situacao.resumo).contains("INDISPONÍVEL", "OneDriveCommercial")

        val backup = org.example.service.BackupService(true, env.getProperty("rfid.backup.pasta")!!, 14, mockk(relaxed = true))
        assertThat(backup.variavelAusente).isEqualTo("OneDriveCommercial")
        assertThat(backup.pasta).isEqualTo(Path.of("data", "backups"))
    }
}
