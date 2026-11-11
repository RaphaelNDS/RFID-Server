package org.example.migracao

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/** V30 (1.14.0): o catálogo do site entra sem repetir nem mudar o que já existe. */
class MigracaoV30Test {

    private val schema = "migracao_v30_${System.nanoTime()}"
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

    private fun linhas(consulta: String): List<List<String?>> = DriverManager.getConnection(url, postgres.username, postgres.password).use { c ->
        c.createStatement().use { st ->
            st.executeQuery(consulta).use { rs ->
                val n = rs.metaData.columnCount
                buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
            }
        }
    }

    @Test
    fun `catalogo do site entra sem duplicar o que ja existe`() {
        flyway("29").migrate()
        // Já cadastrados à mão, com outra grafia: ficam como estão, sem cópia
        sql(
            "insert into tipo_equipamento (id, nome) values (100, 'coletor de dados')",
            "insert into marca (id, tipo_id, nome) values (200, 100, 'ZEBRA')",
            "insert into modelo (marca_id, nome) values (200, 'mc33')",
            "insert into modelo (marca_id, nome) values (200, 'Modelo Próprio')"
        )

        assertThat(flyway().migrate().success).isTrue()

        assertThat(linhas("select nome from tipo_equipamento where lower(nome) = 'coletor de dados'")).containsExactly(listOf("coletor de dados"))
        assertThat(linhas("select count(*) from marca where tipo_id = 100 and lower(nome) = 'zebra'")).containsExactly(listOf("1"))
        assertThat(linhas("select count(*) from modelo where marca_id = 200 and lower(nome) = 'mc33'")).containsExactly(listOf("1"))
        assertThat(linhas("select nome from modelo where marca_id = 200 and nome = 'Modelo Próprio'")).hasSize(1)
        assertThat(linhas("select nome from modelo where marca_id = 200 and nome = 'TC21'")).hasSize(1)

        // Os tipos do site, sem os que o usuário tirou
        val tipos = linhas("select nome from tipo_equipamento").map { it[0] }
        assertThat(tipos).contains("Impressora de Etiquetas", "Impressora RFID", "Leitor de Código de Barras", "Balança", "Monitor Touch")
        assertThat(tipos).doesNotContain("CPU para PDV", "Etiqueta Eletrônica de Gôndola", "Terminal de Autoatendimento")
        assertThat(linhas("select count(*) from marca m join tipo_equipamento t on t.id = m.tipo_id where t.nome = 'Monitor Touch'")).containsExactly(listOf("1"))

        // Rodar de novo (reaplicar o mesmo conteúdo) não duplica: confere pelo índice único que a V17 criou
        assertThat(linhas("select count(*) from (select marca_id, lower(nome) from modelo group by 1, 2 having count(*) > 1) d")).containsExactly(listOf("0"))
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
