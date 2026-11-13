package org.example.migracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * V34 (2.0.0): as situações da locação viram as quatro do sistema simples, os perfis e módulos que saíram são
 * convertidos, as tabelas de movimentação nascem e as tabelas antigas da locação continuam no banco.
 */
class MigracaoV34Test {

    private val schema = "migracao_v34_${System.nanoTime()}"
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

    private fun permissoes(usuario: Long): Set<String> =
        linhas("select modulo || '_' || acao from permissoes where usuario_id = $usuario").map { it[0]!! }.toSet()

    @Test
    fun `situacoes da locacao viram as do estoque, com historico, e vendido vira baixado com o motivo Venda`() {
        flyway("33").migrate()
        sql(
            "insert into tags (id, tag_hash, tag_real, codigo_interno, situacao) values " +
                "(1, 'h1', 'E1', 'T1', 'ALUGADO'), (2, 'h2', 'E2', 'T2', 'EM_DEVOLUCAO'), (3, 'h3', 'E3', 'T3', 'EM_MANUTENCAO'), " +
                "(4, 'h4', 'E4', 'T4', 'A_VENDA'), (5, 'h5', 'E5', 'T5', 'VENDIDO'), (6, 'h6', 'E6', 'T6', 'DISPONIVEL'), " +
                "(7, 'h7', 'E7', 'T7', 'EXTRAVIADO'), (8, 'h8', 'E8', 'T8', 'EM_USO'), (9, 'h9', 'E9', 'T9', 'VENDIDO')",
            "insert into tags (id, tag_hash, tag_real, codigo_interno, situacao, baixa_motivo, baixa_em, baixa_por) values " +
                "(10, 'h10', 'E10', 'T10', 'BAIXADO', 'SUCATA', date '2026-01-10', 'ana@x.com')",
            "insert into vendas (tag_id, tag_real, data, registrada_por) values (5, 'E5', date '2026-10-20', 'ana@x.com')"
        )

        assertThat(flyway("34").migrate().success).isTrue()

        assertThat(linhas("select tag_real, situacao from tags order by id")).containsExactly(
            listOf("E1", "FORA"), listOf("E2", "FORA"), listOf("E3", "FORA"), listOf("E4", "DISPONIVEL"), listOf("E5", "BAIXADO"),
            listOf("E6", "DISPONIVEL"), listOf("E7", "EXTRAVIADO"), listOf("E8", "DISPONIVEL"), listOf("E9", "BAIXADO"), listOf("E10", "BAIXADO"))
        // Vendido: baixado pela Venda, na data da venda (sem venda registrada, a data da migração)
        assertThat(linhas("select baixa_motivo, baixa_em::text, baixa_por from tags where id = 5"))
            .containsExactly(listOf("VENDA", "2026-10-20", "sistema"))
        assertThat(linhas("select baixa_motivo, baixa_em is not null from tags where id = 9")).containsExactly(listOf("VENDA", "t"))
        // O baixado de antes continua como estava
        assertThat(linhas("select baixa_motivo, baixa_por from tags where id = 10")).containsExactly(listOf("SUCATA", "ana@x.com"))
        // Cada conversão no histórico da etiqueta; o que já estava certo, não
        assertThat(linhas("select tag_real, campo, valor_antigo, valor_novo from historico_etiquetas order by tag_real")).containsExactlyInAnyOrder(
            listOf("E1", "Situação", "Alugado", "Fora do estoque"), listOf("E2", "Situação", "Em devolução", "Fora do estoque"),
            listOf("E3", "Situação", "Em manutenção", "Fora do estoque"), listOf("E4", "Situação", "À venda", "No estoque"),
            listOf("E5", "Situação", "Vendido", "Baixado"), listOf("E8", "Situação", "Em uso", "No estoque"),
            listOf("E9", "Situação", "Vendido", "Baixado"))

        // A situação só aceita as quatro de agora
        assertThatThrownBy { sql("update tags set situacao = 'ALUGADO' where id = 6") }.hasMessageContaining("ck_tags_situacao")
    }

    @Test
    fun `perfis de funcao viram personalizado e locacao e manutencao viram movimentacoes`() {
        flyway("33").migrate()
        sql(
            "insert into usuarios (id, nome, email, senha, role, ativo) values " +
                "(1, 'Ana', 'ana@x.com', 'h', 'ADMIN', true), (2, 'Gil', 'gil@x.com', 'h', 'GESTOR', true), " +
                "(3, 'Uri', 'uri@x.com', 'h', 'USER', true), (4, 'Eva', 'eva@x.com', 'h', 'EXPEDICAO', true), " +
                "(5, 'Teo', 'teo@x.com', 'h', 'TECNICO', true), (6, 'Cid', 'cid@x.com', 'h', 'CUSTOM', true)",
            "insert into permissoes (usuario_id, modulo, acao) values " +
                "(2, 'RFID', 'READ'), (2, 'LOCACAO', 'READ'), (2, 'LOCACAO', 'CREATE'), (2, 'LOCACAO', 'UPDATE'), (2, 'MANUTENCAO', 'READ'), " +
                "(3, 'RFID', 'READ'), (3, 'LOCACAO', 'READ'), " +
                "(4, 'LOCACAO', 'READ'), (4, 'LOCACAO', 'CREATE'), (4, 'LOCACAO', 'UPDATE'), (4, 'RFID', 'READ'), (4, 'LEITURA', 'READ'), " +
                "(5, 'MANUTENCAO', 'READ'), (5, 'MANUTENCAO', 'CREATE'), (5, 'MANUTENCAO', 'UPDATE'), (5, 'LOCACAO', 'READ'), (5, 'RFID', 'UPDATE'), " +
                "(6, 'CATALOGO', 'READ')"
        )

        assertThat(flyway("34").migrate().success).isTrue()

        assertThat(linhas("select id, role from usuarios order by id")).containsExactly(
            listOf("1", "ADMIN"), listOf("2", "GESTOR"), listOf("3", "USER"), listOf("4", "CUSTOM"), listOf("5", "CUSTOM"), listOf("6", "CUSTOM"))
        assertThat(permissoes(1)).containsExactlyInAnyOrder("MOVIMENTO_READ", "MOVIMENTO_CREATE", "MOVIMENTO_UPDATE", "MOVIMENTO_DELETE")
        assertThat(permissoes(2)).containsExactlyInAnyOrder("RFID_READ", "MOVIMENTO_READ", "MOVIMENTO_CREATE", "MOVIMENTO_UPDATE")
        assertThat(permissoes(3)).containsExactlyInAnyOrder("RFID_READ", "MOVIMENTO_READ")
        assertThat(permissoes(4)).containsExactlyInAnyOrder("RFID_READ", "LEITURA_READ", "MOVIMENTO_READ", "MOVIMENTO_CREATE", "MOVIMENTO_UPDATE")
        assertThat(permissoes(5)).containsExactlyInAnyOrder("RFID_UPDATE", "MOVIMENTO_READ", "MOVIMENTO_CREATE", "MOVIMENTO_UPDATE")
        assertThat(permissoes(6)).containsExactly("CATALOGO_READ")
        assertThat(linhas("select count(*) from permissoes where modulo in ('LOCACAO', 'MANUTENCAO')")).containsExactly(listOf("0"))
        // Sem permissão repetida
        assertThat(linhas("select count(*) from (select usuario_id, modulo, acao from permissoes group by 1, 2, 3 having count(*) > 1) d"))
            .containsExactly(listOf("0"))
    }

    @Test
    fun `tabelas novas de movimentacao e as antigas da locacao continuam no banco`() {
        flyway("33").migrate()
        assertThat(flyway("34").migrate().success).isTrue()

        val tabelas = linhas("select table_name from information_schema.tables where table_schema = '$schema'").map { it[0] }
        assertThat(tabelas).contains("movimentos", "movimento_itens")
        assertThat(tabelas).contains("pedidos_locacao", "clientes", "vendas", "manutencoes", "transferencias", "anexos_bem",
            "acessorios", "kit_itens", "pedido_acessorios", "responsaveis", "emprestimos")

        sql("insert into tags (id, tag_hash, tag_real, codigo_interno) values (1, 'h1', 'E1', 'T1')")
        // Saída sem destino é recusada pelo banco; entrada sem destino pode
        assertThatThrownBy {
            sql("insert into movimentos (tipo, data_hora, registrado_por, origem) values ('SAIDA', now(), 'x', 'WEB')")
        }.hasMessageContaining("ck_movimentos_destino")
        sql(
            "insert into movimentos (id, tipo, data_hora, registrado_por, origem) values (1, 'ENTRADA', now(), 'x', 'COLETOR')",
            "insert into movimento_itens (movimento_id, tag_id, tag_real) values (1, 1, 'E1')",
            // A etiqueta excluída continua no comprovante
            "delete from tags where id = 1"
        )
        assertThat(linhas("select tag_id, tag_real from movimento_itens")).containsExactly(listOf(null, "E1"))
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
