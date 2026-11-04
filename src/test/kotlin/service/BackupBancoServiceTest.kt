package org.example.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

class BackupBancoServiceTest {

    @TempDir
    lateinit var pasta: Path

    // "Agora" fixo: 04/11/2026 15:00
    private val relogio = Clock.fixed(LocalDateTime.of(2026, 11, 4, 15, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault())

    private fun service() = BackupBancoService(pasta.toString(), 12, relogio)

    private fun dump(nome: String, bytes: Int = 1000) {
        Files.createDirectories(pasta.resolve("last"))
        Files.write(pasta.resolve("last").resolve(nome), ByteArray(bytes))
    }

    @Test
    fun `backup recente esta em dia`() {
        dump("rfid-20261104-093900.dump")
        dump("rfid-latest.dump", bytes = 0) // atalho: aparece vazio no Windows e é ignorado

        val s = service().situacao()

        assertThat(s.atrasado).isFalse()
        assertThat(s.ultimaTentativaFalhou).isFalse()
        assertThat(s.resumo).isEqualTo("04/11/2026 09:39 (há 5h)")
    }

    @Test
    fun `sem backup ha mais de 12 horas esta atrasado`() {
        dump("rfid-20261103-172157.dump")

        val s = service().situacao()

        assertThat(s.atrasado).isTrue()
        assertThat(s.resumo).contains("03/11/2026 17:21").contains("ATRASADO")
    }

    @Test
    fun `ultima tentativa vazia e avisada e o ultimo bom continua valendo`() {
        dump("rfid-20261103-172157.dump")
        dump("rfid-20261104-083918.dump", bytes = 0) // o que aconteceu em 04/11: banco ainda subindo

        val s = service().situacao()

        assertThat(s.ultimaTentativaFalhou).isTrue()
        assertThat(s.momentoUltimoBom).isEqualTo(LocalDateTime.of(2026, 11, 3, 17, 21, 57))
        assertThat(s.resumo).contains("a última tentativa (04/11/2026 08:39) gerou um arquivo vazio")
    }

    @Test
    fun `pasta sem nenhum backup esta atrasada`() {
        Files.createDirectories(pasta.resolve("last"))

        val s = service().situacao()

        assertThat(s.atrasado).isTrue()
        assertThat(s.resumo).isEqualTo("nenhum backup encontrado")
    }

    @Test
    fun `pasta que nao existe nao vira alarme`() {
        val s = BackupBancoService(pasta.resolve("nao-existe").toString(), 12, relogio).situacao()

        assertThat(s.pastaEncontrada).isFalse()
        assertThat(s.atrasado).isFalse()
        assertThat(s.resumo).contains("não encontrada")
    }
}
