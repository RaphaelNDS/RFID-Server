package org.example.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Backup do certificado HTTPS, com arquivos de verdade numa pasta temporária (o banco tem backup próprio). */
class BackupServiceTest {

    @TempDir
    lateinit var dir: Path

    private val p12 get() = dir.resolve("cert/rfid-https.p12")
    private val senha get() = dir.resolve("cert/rfid-https.p12.senha")

    @BeforeEach
    fun criarCertificado() {
        Files.createDirectories(p12.parent)
        Files.write(p12, byteArrayOf(1, 2, 3, 4))
        Files.writeString(senha, "senha-gerada")
    }

    private fun service(
        habilitado: Boolean = true,
        pasta: String = dir.resolve("backups").toString(),
        manter: Int = 14,
        certificado: CertificadoHttps = CertificadoHttps(p12.toString(), "", "")
    ) = BackupService(habilitado, pasta, manter, certificado)

    private fun nomeEm(quando: LocalDateTime) =
        "rfid-certificado-${quando.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))}.zip"

    private fun conteudo(zip: Path, nome: String): ByteArray? =
        ZipFile(zip.toFile()).use { z -> z.getEntry(nome)?.let { e -> z.getInputStream(e).use { it.readBytes() } } }

    @Nested
    inner class Ativacao {

        @Test
        fun `sem certificado em disco (HTTPS desligado) nao ha backup`() {
            Files.delete(p12)
            Files.delete(senha)
            val s = service()

            assertThat(s.ativo).isFalse()
            assertThat(s.motivoInativo()).isEqualTo("nenhum certificado HTTPS para guardar")
            assertThat(s.resumoUltimoBackup()).startsWith("inativo")
            assertThatThrownBy { s.fazerBackup("teste") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("nenhum certificado HTTPS")
        }

        @Test
        fun `pode ser desligado na configuracao`() {
            val s = service(habilitado = false)

            assertThat(s.ativo).isFalse()
            assertThat(s.motivoInativo()).contains("rfid.backup.habilitado=false")
        }

        @Test
        fun `sem pasta configurada usa data-backups`() {
            assertThat(service(pasta = "  ").pasta).isEqualTo(Path.of("data", "backups"))
        }
    }

    @Nested
    inner class Backup {

        @Test
        fun `gera um zip com o certificado e a senha e sem arquivo parcial`() {
            val arquivo = service().fazerBackup("teste")

            assertThat(arquivo.name).matches("""rfid-certificado-\d{4}-\d{2}-\d{2}_\d{6}\.zip""")
            assertThat(conteudo(arquivo, "rfid-https.p12")).containsExactly(1, 2, 3, 4)
            assertThat(conteudo(arquivo, "rfid-https.p12.senha")).isEqualTo("senha-gerada".toByteArray())
            assertThat(dir.resolve("backups").listDirectoryEntries().map { it.name }).noneMatch { it.endsWith(".parcial") }
        }

        @Test
        fun `extrair o zip na pasta data restaura o certificado`() {
            val arquivo = service().fazerBackup("teste")

            val restaurado = dir.resolve("restaurado")
            Files.createDirectories(restaurado)
            ZipFile(arquivo.toFile()).use { zip ->
                zip.entries().asSequence().forEach { e -> zip.getInputStream(e).use { Files.copy(it, restaurado.resolve(e.name)) } }
            }

            // O CertificadoHttps do servidor restaurado encontra os mesmos arquivos no caminho padrão
            val restauradoCert = CertificadoHttps(restaurado.resolve("rfid-https.p12").toString(), "", "")
            assertThat(restauradoCert.arquivosParaBackup().map { it.name })
                .containsExactlyInAnyOrder("rfid-https.p12", "rfid-https.p12.senha")
            assertThat(Files.readAllBytes(restaurado.resolve("rfid-https.p12"))).containsExactly(1, 2, 3, 4)
        }

        @Test
        fun `com senha configurada so o p12 existe e e o unico copiado`() {
            Files.delete(senha)

            val arquivo = service().fazerBackup("teste")

            assertThat(conteudo(arquivo, "rfid-https.p12")).isNotNull()
            assertThat(conteudo(arquivo, "rfid-https.p12.senha")).isNull()
        }

        @Test
        fun `mantem so os mais recentes e apaga os antigos`() {
            val pasta = dir.resolve("backups")
            Files.createDirectories(pasta)
            val agora = LocalDateTime.now()
            (1..5L).forEach { Files.writeString(pasta.resolve(nomeEm(agora.minusDays(it))), "antigo") }

            service(manter = 3).fazerBackup("teste")

            val restantes = pasta.listDirectoryEntries().map { it.name }.sorted()
            assertThat(restantes).hasSize(3)
            assertThat(restantes).containsExactly(nomeEm(agora.minusDays(2)), nomeEm(agora.minusDays(1)), restantes.last())
        }

        @Test
        fun `arquivos que nao sao backups do certificado ficam intactos`() {
            val pasta = dir.resolve("backups")
            Files.createDirectories(pasta)
            Files.writeString(pasta.resolve("leia-me.txt"), "x")
            // Backups antigos do banco H2 (de antes do PostgreSQL) não são apagados pela limpeza
            Files.writeString(pasta.resolve("rfid-db-2026-10-13_144412.zip"), "x")

            service(manter = 1).fazerBackup("teste")

            assertThat(pasta.resolve("leia-me.txt")).exists()
            assertThat(pasta.resolve("rfid-db-2026-10-13_144412.zip")).exists()
        }

        @Test
        fun `dois backups no mesmo segundo nao falham`() {
            val s = service()

            s.fazerBackup("primeiro")
            s.fazerBackup("segundo")

            assertThat(s.listarBackups()).isNotEmpty()
        }
    }

    @Nested
    inner class Inicializacao {

        @Test
        fun `sem nenhum backup faz um ao subir`() {
            val s = service()

            s.verificarNaInicializacao()

            assertThat(s.listarBackups()).hasSize(1)
        }

        @Test
        fun `com backup recente nao faz outro`() {
            val pasta = dir.resolve("backups")
            Files.createDirectories(pasta)
            Files.writeString(pasta.resolve(nomeEm(LocalDateTime.now().minusHours(3))), "recente")

            service().verificarNaInicializacao()

            assertThat(pasta.listDirectoryEntries()).hasSize(1)
        }

        @Test
        fun `com backup de mais de 24h faz um novo`() {
            val pasta = dir.resolve("backups")
            Files.createDirectories(pasta)
            Files.writeString(pasta.resolve(nomeEm(LocalDateTime.now().minusDays(2))), "velho")

            service().verificarNaInicializacao()

            assertThat(pasta.listDirectoryEntries()).hasSize(2)
        }

        @Test
        fun `agendado com backup inativo nao faz nada nem falha`() {
            service(habilitado = false).backupAgendado()
        }
    }

    @Test
    fun `resumo mostra data e idade do ultimo backup`() {
        val pasta = dir.resolve("backups")
        Files.createDirectories(pasta)
        val quando = LocalDateTime.now().minusHours(5).withSecond(0)
        Files.writeString(pasta.resolve(nomeEm(quando)), "x")

        assertThat(service().resumoUltimoBackup())
            .isEqualTo("${quando.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))} (há 5h)")
    }

    @Test
    fun `resumo sem backups avisa`() {
        assertThat(service().resumoUltimoBackup()).isEqualTo("nenhum backup ainda")
    }
}
