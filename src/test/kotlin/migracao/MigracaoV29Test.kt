package org.example.migracao

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * V29 (locadora, 1.12.0): um banco na V28 com empréstimos da 1.10.0 vira pedidos de locação com o mesmo número, os
 * responsáveis dos empréstimos viram clientes, e as situações antigas viram as da locadora.
 */
class MigracaoV29Test {

    private val schema = "migracao_v29_${System.nanoTime()}"
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
    fun `emprestimos viram pedidos e as situacoes antigas viram as da locadora`() {
        flyway("28").migrate()
        sql(
            "insert into responsaveis (id, nome, setor, matricula, email) values (7, 'Rita Souza', 'TI', '123', 'rita@x.com')",
            "insert into tags (id, tag_hash, tag_real, codigo_interno, patrimonio, situacao) values " +
                "(1, 'h1', 'E1', 'TI-1', 'P1', 'EMPRESTADO'), (2, 'h2', 'E2', 'TI-2', 'P2', 'EM_USO'), " +
                "(3, 'h3', 'E3', 'TI-3', 'P3', 'EM_ESTOQUE'), (4, 'h4', 'E4', 'TI-4', 'P4', 'EM_MANUTENCAO')",
            "insert into emprestimos (id, responsavel_id, responsavel, data_saida, previsao_devolucao, finalidade, registrado_por) " +
                "values (5, 7, 'Rita Souza (TI, mat. 123)', date '2026-11-01', date '2026-11-20', 'Evento', 'ana@x.com')",
            // E1 ainda emprestado; E2 já voltou
            "insert into emprestimo_itens (id, emprestimo_id, tag_id, tag_real, patrimonio, descricao, situacao_anterior) values (10, 5, 1, 'E1', 'P1', 'Notebook', 'EM_USO')",
            "insert into emprestimo_itens (id, emprestimo_id, tag_id, tag_real, patrimonio, situacao_anterior, devolvido_em, devolvido_por, condicao_devolucao) " +
                "values (11, 5, 2, 'E2', 'P2', 'EM_USO', date '2026-11-05', 'ana@x.com', 'Sem avarias')",
            "insert into manutencoes (tag_id, tag_real, tipo, problema, data_envio, situacao_anterior, status, registrada_por) " +
                "values (4, 'E4', 'CORRETIVA', 'Não liga', date '2026-11-02', 'EM_ESTOQUE', 'ABERTA', 'ana@x.com')"
        )

        assertThat(flyway("29").migrate().success).isTrue()

        assertThat(linhas("select tag_real, situacao from tags order by id")).containsExactly(
            listOf("E1", "ALUGADO"), listOf("E2", "DISPONIVEL"), listOf("E3", "DISPONIVEL"), listOf("E4", "EM_MANUTENCAO"))
        assertThat(linhas("select situacao_anterior from manutencoes")).containsExactly(listOf("DISPONIVEL"))

        assertThat(linhas("select nome, contato, email, origem_responsavel_id from clientes"))
            .containsExactly(listOf("Rita Souza", "TI, mat. 123", "rita@x.com", "7"))
        assertThat(linhas("select id, cliente, situacao, separacao_concluida, observacao from pedidos_locacao"))
            .containsExactly(listOf("5", "Rita Souza (TI, mat. 123)", "DEVOLVIDO_PARCIAL", "t", "Evento"))
        assertThat(linhas("select id, especifico, tag_id, quantidade from pedido_linhas order by id"))
            .containsExactly(listOf("10", "t", "1", "1"), listOf("11", "t", "2", "1"))
        assertThat(linhas("select id, linha_id, tag_real, devolvido_em is not null, conferido_em is not null, estado_devolucao from pedido_itens order by id"))
            .containsExactly(listOf("10", "10", "E1", "f", "f", null), listOf("11", "11", "E2", "t", "t", "OK"))

        // Os próximos números continuam depois dos convertidos, e o E1 continua preso ao pedido (índice único)
        sql("insert into pedidos_locacao (cliente_id, cliente, previsao_devolucao, situacao, registrado_por) select id, nome, date '2026-12-01', 'ABERTO', 'x' from clientes")
        assertThat(linhas("select max(id) from pedidos_locacao")).containsExactly(listOf("6"))
        sql("insert into pedido_linhas (pedido_id, tipo_equipamento_id, descricao, quantidade, especifico) values (6, null, 'E1', 1, true)")
        assertThat(linhas("select max(id) from pedido_linhas")).containsExactly(listOf("12"))
        val duplicado = runCatching {
            sql("insert into pedido_itens (pedido_id, linha_id, tag_id, tag_real, saida_em, saida_por) values (6, 12, 1, 'E1', now(), 'x')")
        }
        assertThat(duplicado.exceptionOrNull()?.message.orEmpty().lowercase()).contains("ux_pedido_itens_em_aberto")
    }

    @Test
    fun `banco sem emprestimos migra e numera os pedidos a partir de 1`() {
        flyway("28").migrate()
        assertThat(flyway("29").migrate().success).isTrue()
        sql(
            "insert into clientes (nome) values ('Cliente')",
            "insert into pedidos_locacao (cliente_id, cliente, previsao_devolucao, situacao, registrado_por) select id, nome, date '2026-12-01', 'ABERTO', 'x' from clientes"
        )
        assertThat(linhas("select id from pedidos_locacao")).containsExactly(listOf("1"))
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
