package org.example.config

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
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
}
