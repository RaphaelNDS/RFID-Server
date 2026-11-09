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

    // ---------- Conferência do dump (1.4.6) ----------

    /** Conferência de mentira: o resultado de cada dump pelo nome, contando quantas vezes cada um foi conferido. */
    private class ConferenciaFalsa(val resultados: Map<String, ResultadoDump>) : ConferenciaDump("comando-de-teste {arquivo}") {
        val chamadas = mutableMapOf<String, Int>()
        override fun conferir(arquivo: Path): ResultadoDump {
            chamadas.merge(arquivo.fileName.toString(), 1, Int::plus)
            return resultados[arquivo.fileName.toString()] ?: ResultadoDump.INTEIRO
        }
    }

    @Test
    fun `dump mais recente incompleto e avisado e vale o anterior inteiro`() {
        dump("rfid-20261104-033900.dump")
        dump("rfid-20261104-093900.dump")   // computador desligado no meio do pg_dump: tem bytes, mas não restaura
        val conferencia = ConferenciaFalsa(mapOf("rfid-20261104-093900.dump" to ResultadoDump.INCOMPLETO))

        val s = BackupBancoService(pasta.toString(), 12, relogio, conferencia).situacao()

        assertThat(s.ultimaTentativaFalhou).isTrue()
        assertThat(s.integridadeConferida).isTrue()
        assertThat(s.momentoUltimoBom).isEqualTo(LocalDateTime.of(2026, 11, 4, 3, 39))
        assertThat(s.resumo).startsWith("04/11/2026 03:39 (há 11h)")
            .contains("a última tentativa (04/11/2026 09:39) gerou um arquivo incompleto, que não restaura")
    }

    @Test
    fun `cada dump e conferido uma vez so`() {
        dump("rfid-20261104-093900.dump")
        val conferencia = ConferenciaFalsa(emptyMap())
        val service = BackupBancoService(pasta.toString(), 12, relogio, conferencia)

        repeat(5) { service.situacao() }

        assertThat(conferencia.chamadas["rfid-20261104-093900.dump"]).isEqualTo(1)
        assertThat(service.situacao().integridadeConferida).isTrue()
        assertThat(service.situacao().resumo).isEqualTo("04/11/2026 09:39 (há 5h)")
    }

    @Test
    fun `sem conseguir conferir vale o tamanho sem alarme e tenta de novo depois`() {
        dump("rfid-20261104-093900.dump")
        val conferencia = ConferenciaFalsa(mapOf("rfid-20261104-093900.dump" to ResultadoDump.NAO_CONFERIDO))
        val service = BackupBancoService(pasta.toString(), 12, relogio, conferencia)

        val s = service.situacao()
        service.situacao()

        assertThat(s.ultimaTentativaFalhou).isFalse()
        assertThat(s.atrasado).isFalse()
        assertThat(s.integridadeConferida).isFalse()
        assertThat(s.resumo).contains("integridade não conferida")
        assertThat(conferencia.chamadas["rfid-20261104-093900.dump"]).isEqualTo(2)   // não guarda o "não conferido"
    }

    @Test
    fun `conferencia desligada mantem o comportamento antigo`() {
        dump("rfid-20261104-093900.dump")

        val s = BackupBancoService(pasta.toString(), 12, relogio, ConferenciaDump("")).situacao()

        assertThat(s.resumo).isEqualTo("04/11/2026 09:39 (há 5h)")
        assertThat(s.integridadeConferida).isFalse()
    }

    @Test
    fun `nome de arquivo fora do padrao nao entra no comando`() {
        val c = ConferenciaDump("echo {arquivo}")
        assertThat(c.conferir(pasta.resolve("rfid-20261104-093900.dump; rm -rf x"))).isEqualTo(ResultadoDump.NAO_CONFERIDO)
    }

    @Test
    fun `pasta que nao existe nao vira alarme`() {
        val s = BackupBancoService(pasta.resolve("nao-existe").toString(), 12, relogio).situacao()

        assertThat(s.pastaEncontrada).isFalse()
        assertThat(s.atrasado).isFalse()
        assertThat(s.resumo).contains("não encontrada")
    }
}
