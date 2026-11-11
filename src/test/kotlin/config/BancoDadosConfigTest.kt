package org.example.config

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.example.repository.LeituraAndroidRepository
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class BancoDadosConfigTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "jdbc:postgresql://localhost:5433/rfid, PostgreSQL (localhost:5433/rfid)",
        "'jdbc:postgresql://srv-banco:5432/rfid?sslmode=require', PostgreSQL (srv-banco:5432/rfid)",
        "jdbc:tc:postgresql:16-alpine:///rfid-teste, PostgreSQL (contêiner de teste)",
        "jdbc:mysql://localhost/rfid, mysql"
    )
    fun `descreve o banco a partir da url`(url: String, esperado: String) {
        val config = BancoDadosConfig(url, mockk(), mockk(), mockk())

        assertThat(config.descricao).isEqualTo(esperado)
    }

    @org.junit.jupiter.api.Test
    fun `leituras na subida vem da estimativa e nunca do count que varre a tabela`() {
        val leituras = mockk<LeituraAndroidRepository>()
        val config = BancoDadosConfig("jdbc:postgresql://x/rfid", mockk(), mockk(), leituras)

        io.mockk.every { leituras.estimativaDoTotal() } returns 1_234_567
        assertThat(config.leiturasRegistradas()).isEqualTo("cerca de 1234567 leitura(s) registrada(s) (estimativa do PostgreSQL)")

        // Sem estatística ainda (-1): só confere se há alguma
        io.mockk.every { leituras.estimativaDoTotal() } returns -1
        io.mockk.every { leituras.existeAlguma() } returns true
        assertThat(config.leiturasRegistradas()).isEqualTo("leituras registradas (total ainda não estimado pelo PostgreSQL)")
        io.mockk.every { leituras.existeAlguma() } returns false
        assertThat(config.leiturasRegistradas()).isEqualTo("nenhuma leitura registrada")

        io.mockk.verify(exactly = 0) { leituras.count() }
    }
}
