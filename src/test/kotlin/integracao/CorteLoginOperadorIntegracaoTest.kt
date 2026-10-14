package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.config.CorteLoginOperador
import org.example.suporte.IntegracaoBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

/**
 * O corte do login de operador precisa ser o momento em que a V4 foi instalada, lido do histórico do Flyway.
 * Regressão: a consulta sem aspas falhava no H2 e o corte virava "a hora de agora" a cada reinício.
 */
class CorteLoginOperadorIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `corte e a data de instalacao da V4, nao a hora em que o servidor subiu`() {
        val instaladaEm = jdbc.queryForObject(
            """select "installed_on" from "flyway_schema_history" where "version" = '4'""",
            LocalDateTime::class.java
        )

        // Instância nova: se a consulta falhasse, o corte seria "agora" (depois da instalação)
        val corte = CorteLoginOperador(jdbc)

        assertThat(corte.momento).isEqualTo(instaladaEm)
    }
}
