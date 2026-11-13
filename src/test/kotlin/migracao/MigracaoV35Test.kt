package org.example.migracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * V35 (2.1.0): as Movimentações saíram. "Fora do estoque" volta para "No estoque" (com histórico), a situação só aceita
 * as três de agora, as permissões do módulo MOVIMENTO somem (Ver vira Etiquetas · Ver para quem não tinha) e as tabelas
 * movimentos e movimento_itens continuam no banco, com o que foi registrado.
 */
class MigracaoV35Test {

    private val schema = "migracao_v35_${System.nanoTime()}"
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
    fun `fora do estoque volta para o estoque, com historico e versao nova, e a situacao so aceita as tres`() {
        flyway("34").migrate()
        sql(
            "insert into tags (id, tag_hash, tag_real, codigo_interno, situacao, versao) values " +
                "(1, 'h1', 'E1', 'T1', 'FORA', 3), (2, 'h2', 'E2', 'T2', 'DISPONIVEL', 0), (3, 'h3', 'E3', 'T3', 'EXTRAVIADO', 0), " +
                "(4, 'h4', 'E4', 'T4', 'FORA', 0)",
            "insert into tags (id, tag_hash, tag_real, codigo_interno, situacao, baixa_motivo, baixa_em, baixa_por) values " +
                "(5, 'h5', 'E5', 'T5', 'BAIXADO', 'SUCATA', date '2026-01-10', 'ana@x.com')"
        )

        assertThat(flyway("35").migrate().success).isTrue()

        assertThat(linhas("select tag_real, situacao, versao from tags order by id")).containsExactly(
            listOf("E1", "DISPONIVEL", "4"), listOf("E2", "DISPONIVEL", "0"), listOf("E3", "EXTRAVIADO", "0"),
            listOf("E4", "DISPONIVEL", "1"), listOf("E5", "BAIXADO", "0"))
        // O baixado continua como estava
        assertThat(linhas("select baixa_motivo, baixa_por from tags where id = 5")).containsExactly(listOf("SUCATA", "ana@x.com"))
        // Cada conversão no histórico, por "sistema"; o que já estava certo, não
        assertThat(linhas("select tag_id, tag_real, acao, campo, valor_antigo, valor_novo, usuario, origem from historico_etiquetas order by tag_real"))
            .containsExactly(
                listOf("1", "E1", "ALTERACAO", "Situação", "Fora do estoque", "No estoque", "sistema", "Atualização 2.1.0 (V35)"),
                listOf("4", "E4", "ALTERACAO", "Situação", "Fora do estoque", "No estoque", "sistema", "Atualização 2.1.0 (V35)"))

        assertThatThrownBy { sql("update tags set situacao = 'FORA' where id = 2") }.hasMessageContaining("ck_tags_situacao")
        sql("update tags set situacao = 'EXTRAVIADO' where id = 2")
    }

    @Test
    fun `permissoes de movimentacoes somem e quem so via movimentacoes passa a ver as etiquetas`() {
        flyway("34").migrate()
        sql(
            "insert into usuarios (id, nome, email, senha, role, ativo) values " +
                "(11, 'Ana', 'ana@x.com', 'h', 'ADMIN', true), (12, 'Gil', 'gil@x.com', 'h', 'GESTOR', true), " +
                "(13, 'Eva', 'eva@x.com', 'h', 'CUSTOM', true), (14, 'Cid', 'cid@x.com', 'h', 'CUSTOM', true), " +
                "(15, 'Teo', 'teo@x.com', 'h', 'CUSTOM', true)",
            "insert into permissoes (usuario_id, modulo, acao) values " +
                "(11, 'RFID', 'READ'), (11, 'RFID', 'UPDATE'), (11, 'MOVIMENTO', 'READ'), (11, 'MOVIMENTO', 'CREATE'), (11, 'MOVIMENTO', 'DELETE'), " +
                "(12, 'RFID', 'READ'), (12, 'MOVIMENTO', 'READ'), (12, 'MOVIMENTO', 'CREATE'), " +
                // Personalizado só com Movimentações (ex.: a antiga Expedição): fica com Etiquetas · Ver
                "(13, 'LEITURA', 'READ'), (13, 'MOVIMENTO', 'READ'), (13, 'MOVIMENTO', 'CREATE'), (13, 'MOVIMENTO', 'UPDATE'), " +
                // Criar sem Ver não vira nada
                "(14, 'CATALOGO', 'READ'), (14, 'MOVIMENTO', 'CREATE'), " +
                "(15, 'CATALOGO', 'READ')"
        )

        assertThat(flyway("35").migrate().success).isTrue()

        assertThat(permissoes(11)).containsExactlyInAnyOrder("RFID_READ", "RFID_UPDATE")
        assertThat(permissoes(12)).containsExactlyInAnyOrder("RFID_READ")
        assertThat(permissoes(13)).containsExactlyInAnyOrder("LEITURA_READ", "RFID_READ")
        assertThat(permissoes(14)).containsExactlyInAnyOrder("CATALOGO_READ")
        assertThat(permissoes(15)).containsExactlyInAnyOrder("CATALOGO_READ")
        assertThat(linhas("select count(*) from permissoes where modulo = 'MOVIMENTO'")).containsExactly(listOf("0"))
        assertThat(linhas("select count(*) from (select usuario_id, modulo, acao from permissoes group by 1, 2, 3 having count(*) > 1) d"))
            .containsExactly(listOf("0"))
    }

    @Test
    fun `as tabelas de movimentacao continuam no banco, com o que foi registrado`() {
        flyway("34").migrate()
        sql(
            "insert into tags (id, tag_hash, tag_real, codigo_interno, situacao) values (1, 'h1', 'E1', 'T1', 'FORA')",
            "insert into movimentos (id, tipo, data_hora, destino, registrado_por, origem) values (1, 'SAIDA', now(), 'Obra Centro', 'ana@x.com', 'WEB')",
            "insert into movimento_itens (movimento_id, tag_id, tag_real) values (1, 1, 'E1')"
        )

        assertThat(flyway("35").migrate().success).isTrue()

        val tabelas = linhas("select table_name from information_schema.tables where table_schema = '$schema'").map { it[0] }
        assertThat(tabelas).contains("movimentos", "movimento_itens")
        assertThat(linhas("select m.tipo, m.destino, i.tag_real from movimentos m join movimento_itens i on i.movimento_id = m.id"))
            .containsExactly(listOf("SAIDA", "Obra Centro", "E1"))
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
