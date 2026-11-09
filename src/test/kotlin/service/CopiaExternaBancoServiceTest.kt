package org.example.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.util.CifraBackup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** Cópia cifrada do banco fora do computador (1.5.1) e a cifra de arquivo aos poucos. */
class CopiaExternaBancoServiceTest {

    @TempDir lateinit var dumps: Path
    @TempDir lateinit var externa: Path
    @TempDir lateinit var temp: Path

    // "Agora" fixo: 09/11/2026 13:00
    private val relogio = Clock.fixed(LocalDateTime.of(2026, 11, 9, 13, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

    private fun dump(nome: String, conteudo: ByteArray = Random.nextBytes(5000)): Path {
        Files.createDirectories(dumps.resolve("last"))
        return dumps.resolve("last").resolve(nome).also { Files.write(it, conteudo) }
    }

    private fun service(senha: String = "senha-de-teste-123", manter: Int = 14, pasta: String = externa.toString()) =
        CopiaExternaBancoService(pasta, manter, 24, senha, BackupBancoService(dumps.toString(), 12, relogio), relogio)

    @Test
    fun `cifra de arquivo aos poucos tem o mesmo formato da cifra na memoria`() {
        val original = Random.nextBytes(300_000)
        val origem = temp.resolve("a.dump").also { Files.write(it, original) }
        val cifrado = temp.resolve("a.dump.cifrado")
        CifraBackup.cifrarArquivo(origem, cifrado, "abc")

        assertThat(CifraBackup.decifrar(Files.readAllBytes(cifrado), "abc")).isEqualTo(original)   // lido pelo jeito antigo
        val volta = temp.resolve("volta.dump")
        CifraBackup.decifrarArquivo(cifrado, volta, "abc")
        assertThat(Files.readAllBytes(volta)).isEqualTo(original)

        val doCertificado = temp.resolve("c.zip.cifrado").also { Files.write(it, CifraBackup.cifrar(original, "abc")) }
        CifraBackup.decifrarArquivo(doCertificado, temp.resolve("c.zip"), "abc")
        assertThat(Files.readAllBytes(temp.resolve("c.zip"))).isEqualTo(original)
    }

    @Test
    fun `senha errada na decifracao avisa e nao deixa arquivo pela metade`() {
        val origem = temp.resolve("a.dump").also { Files.write(it, Random.nextBytes(1000)) }
        CifraBackup.cifrarArquivo(origem, temp.resolve("a.cifrado"), "certa")
        val destino = temp.resolve("saida.dump")

        assertThatThrownBy { CifraBackup.decifrarArquivo(temp.resolve("a.cifrado"), destino, "errada") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Senha do backup errada")
        assertThat(destino).doesNotExist()
    }

    @Test
    fun `copia o ultimo dump cifrado e so uma vez`() {
        dump("rfid-20261109-060000.dump")
        val conteudo = Random.nextBytes(20_000)
        dump("rfid-20261109-120000.dump", conteudo)
        val s = service()

        val copiado = s.copiar()!!
        assertThat(copiado.fileName.toString()).isEqualTo("rfid-20261109-120000.dump.cifrado")
        assertThat(Files.readAllBytes(copiado)).isNotEqualTo(conteudo)   // não vai aberto
        val volta = temp.resolve("volta.dump")
        CifraBackup.decifrarArquivo(copiado, volta, "senha-de-teste-123")
        assertThat(Files.readAllBytes(volta)).isEqualTo(conteudo)

        assertThat(s.copiar()).isNull()   // já está lá
        val situacao = s.situacao()
        assertThat(situacao.comProblema).isFalse()
        assertThat(situacao.resumo).startsWith("09/11/2026 12:00 (há 1h)")
        assertThat(Files.list(externa).use { it.toList() }).hasSize(1)
    }

    @Test
    fun `guarda so as mais recentes`() {
        val s = service(manter = 2)
        listOf("060000", "120000", "180000").forEachIndexed { i, hora ->
            dump("rfid-2026110${i + 1}-$hora.dump")
            s.copiar()
        }
        assertThat(Files.list(externa).use { a -> a.map { it.fileName.toString() }.sorted().toList() })
            .containsExactly("rfid-20261102-120000.dump.cifrado", "rfid-20261103-180000.dump.cifrado")
    }

    @Test
    fun `sem senha nao copia e mostra o problema`() {
        dump("rfid-20261109-120000.dump")
        val s = service(senha = "")

        assertThat(s.copiar()).isNull()
        assertThat(Files.list(externa).use { it.toList() }).isEmpty()
        assertThat(s.situacao().comProblema).isTrue()
        assertThat(s.situacao().resumo).contains("sem a senha do backup")
    }

    @Test
    fun `sem copia ha mais de 24 horas esta atrasada e desligada nao e problema`() {
        dump("rfid-20261107-120000.dump")
        val s = service()
        s.copiar()
        assertThat(s.situacao().atrasada).isTrue()
        assertThat(s.situacao().resumo).contains("ATRASADA")

        val desligada = service(pasta = "")
        assertThat(desligada.copiar()).isNull()
        assertThat(desligada.situacao().ligada).isFalse()
        assertThat(desligada.situacao().comProblema).isFalse()
    }
}
