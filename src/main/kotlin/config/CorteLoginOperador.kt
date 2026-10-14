package org.example.config

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Momento em que o login de operador passou a valer neste servidor: quando a migração V4 foi instalada.
 *
 * Os coletores guardam leituras feitas offline. As feitas ANTES da atualização não têm operador e
 * seriam recusadas para sempre, travando a fila do coletor. Por isso, uma ação sem operador é
 * aceita só se aconteceu antes deste corte; depois dele, o login é obrigatório.
 */
@Component
class CorteLoginOperador(jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    val momento: LocalDateTime = try {
        // Nomes entre aspas: o Flyway cria a tabela e as colunas em minúsculas, e o H2 converte nome sem
        // aspas para MAIÚSCULAS. Sem as aspas a consulta falhava em toda inicialização e o corte virava
        // "a hora de agora", reabrindo a janela sem operador a cada reinício do servidor.
        jdbc.queryForObject(
            """select "installed_on" from "flyway_schema_history" where "version" = '4' and "success" = true""",
            LocalDateTime::class.java
        ) ?: LocalDateTime.now()
    } catch (e: DataAccessException) {
        log.warn("Não foi possível ler quando o login de operador foi instalado (migração V4): usando a hora de agora", e)
        LocalDateTime.now()
    }

    init {
        log.info("Login de operador obrigatório para ações dos coletores a partir de {}", momento)
    }
}
