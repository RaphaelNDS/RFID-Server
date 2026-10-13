package org.example.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Backup com um banco H2 em arquivo de verdade, numa pasta temporária. */
class BackupServiceTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var urlBanco: String

    @BeforeEach
    fun criarBanco() {
        urlBanco = "jdbc:h2:file:${dir.resolve("banco/rfid-db").toString().replace("\\", "/")}"
        JdbcTemplate(DriverManagerDataSource(urlBanco, "sa", "")).execute(
            "create table etiquetas (epc varchar(50)); insert into etiquetas values ('E2801170AAAA')"
        )
    }

    private fun service(
        url: String = urlBanco,
        habilitado: Boolean = true,
        pasta: String = dir.resolve("backups").toString(),
        manter: Int = 14
    ) = BackupService(DriverManagerDataSource(url, "sa", ""), url, habilitado, pasta, manter)

    private fun nomeEm(quando: LocalDateTime) =
        "rfid-db-${quando.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))}.zip"

    @Nested
    inner class Ativacao {

        @Test
        fun `banco em memoria nao tem backup`() {
            val s = service(url = "jdbc:h2:mem:qualquer")

            assertThat(s.ativo).isFalse()
            assertThat(s.motivoInativo()).isEqualTo("banco em memória")
            assertThat(s.resumoUltimoBackup()).startsWith("inativo")
            assertThatThrownBy { s.fazerBackup("teste") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("banco em memória")
        }

        @Test
        fun `pode ser desligado na configuracao`() {
            val s = service(habilitado = false)

            assertThat(s.ativo).isFalse()
            assertThat(s.motivoInativo()).contains("rfid.backup.habilitado=false")
        }

        @Test
        fun `sem pasta configurada usa backups ao lado do banco`() {
            val s = service(pasta = "  ")

            assertThat(s.pasta.toAbsolutePath()).isEqualTo(dir.resolve("banco/backups").toAbsolutePath())
        }
    }

    @Nested
    inner class Backup {

        @Test
        fun `gera um zip valido com o banco e sem arquivo parcial`() {
            val arquivo = service().fazerBackup("teste")

            assertThat(arquivo.name).matches("""rfid-db-\d{4}-\d{2}-\d{2}_\d{6}\.zip""")
            ZipFile(arquivo.toFile()).use { zip ->
                assertThat(zip.entries().asSequence().map { it.name }.toList()).contains("rfid-db.mv.db")
            }
            assertThat(dir.resolve("backups").listDirectoryEntries().map { it.name }).noneMatch { it.endsWith(".parcial") }
        }

        @Test
        fun `o backup restaura os dados`() {
            val arquivo = service().fazerBackup("teste")

            // Restauração como no manual: extrai o .zip numa pasta e abre o banco
            val restaurado = dir.resolve("restaurado")
            Files.createDirectories(restaurado)
            ZipFile(arquivo.toFile()).use { zip ->
                zip.entries().asSequence().forEach { e -> zip.getInputStream(e).use { Files.copy(it, restaurado.resolve(e.name)) } }
            }

            val urlRestaurada = "jdbc:h2:file:${restaurado.resolve("rfid-db").toString().replace("\\", "/")}"
            DriverManager.getConnection(urlRestaurada, "sa", "").use { c ->
                c.createStatement().executeQuery("select epc from etiquetas").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).isEqualTo("E2801170AAAA")
                }
            }
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
        fun `arquivos que nao sao backups ficam intactos`() {
            val pasta = dir.resolve("backups")
            Files.createDirectories(pasta)
            Files.writeString(pasta.resolve("leia-me.txt"), "x")
            Files.writeString(pasta.resolve("copia-manual.zip"), "x")

            service(manter = 1).fazerBackup("teste")

            assertThat(pasta.resolve("leia-me.txt")).exists()
            assertThat(pasta.resolve("copia-manual.zip")).exists()
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
            service(url = "jdbc:h2:mem:x").backupAgendado()
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
