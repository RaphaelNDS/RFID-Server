package org.example.config

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class BancoDadosConfigTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "jdbc:h2:mem:rfid-db, H2 (memória)",
        "'jdbc:h2:file:./data/rfid-db;WRITE_DELAY=0', H2 (arquivo)",
        "jdbc:h2:~/rfid, H2 (arquivo)",
        "jdbc:postgresql://localhost:5432/rfid, PostgreSQL",
        "jdbc:mysql://localhost/rfid, mysql"
    )
    fun `descreve o banco a partir da url`(url: String, esperado: String) {
        val config = BancoDadosConfig(url, mockk(), mockk(), mockk())

        assertThat(config.descricao).isEqualTo(esperado)
    }
}
