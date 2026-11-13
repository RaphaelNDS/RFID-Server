package org.example.migracao

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/** V31 (1.15.0): quem tinha RFID com uma ação ganha LOCACAO e MANUTENCAO com a mesma ação; ninguém perde acesso. */
class MigracaoV31Test {

    private val schema = "migracao_v31_${System.nanoTime()}"
    private val url = postgres.jdbcUrl + (if ("?" in postgres.jdbcUrl) "&" else "?") + "currentSchema=$schema"

    private fun flyway(alvo: String? = null) = Flyway.configure()
        .dataSource(url, postgres.username, postgres.password)
        .schemas(schema)
        .locations("classpath:db/migration")
        .apply { if (alvo != null) target(alvo) }
        .load()

    private fun sql(vararg comandos: String) = DriverManager.getConnection(url, postgres.username, postgres.password).use { c ->
        c.createStatement().use { st -> comandos.forEach { st.execute(it) } }
    }

    private fun permissoes(usuario: Long): Set<String> = DriverManager.getConnection(url, postgres.username, postgres.password).use { c ->
        c.createStatement().use { st ->
            st.executeQuery("select modulo || '_' || acao from permissoes where usuario_id = $usuario").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

    @Test
    fun `quem tinha RFID ganha locacao e manutencao com as mesmas acoes`() {
        flyway("30").migrate()
        sql(
            "insert into usuarios (id, nome, email, senha, role, ativo) values (1, 'Gil', 'gil@x.com', 'h', 'GESTOR', true), (2, 'Lia', 'lia@x.com', 'h', 'CUSTOM', true)",
            "insert into permissoes (usuario_id, modulo, acao) values (1, 'RFID', 'READ'), (1, 'RFID', 'UPDATE'), (1, 'CATALOGO', 'READ'), (2, 'LEITURA', 'READ')",
            // Já tinha uma: não repete
            "insert into permissoes (usuario_id, modulo, acao) values (1, 'LOCACAO', 'READ')"
        )

        assertThat(flyway("31").migrate().success).isTrue()

        assertThat(permissoes(1)).containsExactlyInAnyOrder("RFID_READ", "RFID_UPDATE", "CATALOGO_READ",
            "LOCACAO_READ", "LOCACAO_UPDATE", "MANUTENCAO_READ", "MANUTENCAO_UPDATE")
        assertThat(permissoes(2)).containsExactly("LEITURA_READ")
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
