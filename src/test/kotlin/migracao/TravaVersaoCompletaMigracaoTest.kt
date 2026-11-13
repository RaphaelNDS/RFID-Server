package org.example.migracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.config.BackupAntesMigracao
import org.example.config.MigracoesConfig
import org.example.config.TravaVersaoCompleta
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * Trava (2.0.0, revista na 2.1.0) num PostgreSQL de verdade, pela mesma estratégia de migração que o servidor usa ao
 * subir: o banco da versão completa (branch locadora) é recusado e NADA é migrado.
 */
class TravaVersaoCompletaMigracaoTest {

    private val schema = "trava_${System.nanoTime()}"
    private val url = postgres.jdbcUrl + (if ("?" in postgres.jdbcUrl) "&" else "?") + "currentSchema=$schema"

    private fun flyway(alvo: String? = null) = Flyway.configure()
        .dataSource(url, postgres.username, postgres.password)
        .schemas(schema)
        .locations("classpath:db/migration")
        .apply { if (alvo != null) target(alvo) }
        .load()

    /** Subir o servidor: a estratégia do MigracoesConfig, com a trava de verdade e sem backup. */
    private fun subir() = MigracoesConfig("").estrategia(BackupAntesMigracao(""), TravaVersaoCompleta()).migrate(flyway())

    private fun sql(vararg comandos: String) = DriverManager.getConnection(url, postgres.username, postgres.password).use { c ->
        c.createStatement().use { st -> comandos.forEach { st.execute(it) } }
    }

    private fun versaoAtual(): String = flyway().info().current().version.toString()

    private fun existe(tabela: String): Boolean = DriverManager.getConnection(url, postgres.username, postgres.password).use { c ->
        c.prepareStatement("select 1 from information_schema.tables where table_schema = ? and table_name = ?").use { ps ->
            ps.setString(1, schema)
            ps.setString(2, tabela)
            ps.executeQuery().use { it.next() }
        }
    }

    /** Uma migração aplicada pela outra versão (a locadora), direto no histórico do Flyway. */
    private fun aplicadaPelaLocadora(versao: String, descricao: String) = sql(
        "insert into flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) " +
            "values ((select max(installed_rank) + 1 from flyway_schema_history), '$versao', '$descricao', 'SQL', " +
            "'V${versao}__${descricao.replace(' ', '_')}.sql', 12345, 'rfid', 1, true)"
    )

    @Test
    fun `banco com pedidos de locacao e recusado e nada e migrado`() {
        flyway("33").migrate()
        sql(
            "insert into clientes (id, nome) values (1, 'Cliente')",
            "insert into pedidos_locacao (cliente_id, cliente, previsao_devolucao, situacao, registrado_por) " +
                "values (1, 'Cliente', date '2026-12-01', 'ABERTO', 'ana@x.com')"
        )

        assertThatThrownBy { subir() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Este banco é da versão completa (branch locadora)")
            .hasMessageContaining("pedidos_locacao")
            .hasMessageContaining("Use o jar da locadora")

        assertThat(versaoAtual()).isEqualTo("33")
        assertThat(existe("movimentos")).isFalse()
    }

    /** Situação de uma etiqueta direto no banco (para ver que a V35 não rodou). */
    private fun situacao(id: Long): String = DriverManager.getConnection(url, postgres.username, postgres.password).use { c ->
        c.createStatement().use { st -> st.executeQuery("select situacao from tags where id = $id").use { it.next(); it.getString(1) } }
    }

    @Test
    fun `banco da locadora sem pedidos mas com migracao depois da V35 e recusado mesmo sem nada pendente`() {
        subir()
        aplicadaPelaLocadora("36", "campos personalizados")

        assertThatThrownBy { subir() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("versão completa (branch locadora)")
            .hasMessageContaining("V36 (campos personalizados)")
    }

    @Test
    fun `banco da 2_0_0 com migracao da locadora depois da V34 e recusado e a V35 nao roda`() {
        flyway("34").migrate()
        sql("insert into tags (id, tag_hash, tag_real, codigo_interno, situacao) values (1, 'h1', 'E1', 'T1', 'FORA')")
        aplicadaPelaLocadora("36", "campos personalizados")

        assertThatThrownBy { subir() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("V36 (campos personalizados)")
        // A V35 deste servidor ficou pendente: nada foi convertido
        assertThat(situacao(1)).isEqualTo("FORA")
    }

    @Test
    fun `V34 da locadora (outra descricao) e recusada antes de migrar`() {
        flyway("33").migrate()
        aplicadaPelaLocadora("34", "restricao clientes")

        assertThatThrownBy { subir() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("a V34 aplicada no banco é \"restricao clientes\"")
        assertThat(existe("movimentos")).isFalse()
        assertThat(versaoAtual()).isEqualTo("34")   // a V34 "da locadora", e nada mais
    }

    @Test
    fun `V35 da locadora (outra descricao) e recusada antes de migrar`() {
        flyway("34").migrate()
        sql("insert into tags (id, tag_hash, tag_real, codigo_interno, situacao) values (1, 'h1', 'E1', 'T1', 'FORA')")
        aplicadaPelaLocadora("35", "revisao automatica")

        assertThatThrownBy { subir() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("versão completa (branch locadora)")
            .hasMessageContaining("a V35 aplicada no banco é \"revisao automatica\", e não \"simples sem movimentacao\"")
        assertThat(situacao(1)).isEqualTo("FORA")
    }

    @Test
    fun `banco da 1_18 sem pedidos e banco novo migram normalmente, e subir de novo passa pela trava`() {
        flyway("33").migrate()
        sql("insert into clientes (id, nome) values (1, 'Cliente sem pedido')")   // a tabela existe, mas sem pedidos
        subir()
        assertThat(versaoAtual()).isEqualTo("35")
        assertThat(existe("movimentos")).isTrue()   // a V34 cria; a V35 não apaga
        subir()   // já em dia: a trava confere de novo e deixa subir
        assertThat(versaoAtual()).isEqualTo("35")
    }

    @Test
    fun `banco da 2_0_0 migra para a V35 normalmente`() {
        flyway("34").migrate()
        sql("insert into tags (id, tag_hash, tag_real, codigo_interno, situacao) values (1, 'h1', 'E1', 'T1', 'FORA')")
        subir()
        assertThat(versaoAtual()).isEqualTo("35")
        assertThat(situacao(1)).isEqualTo("DISPONIVEL")
    }

    @Test
    fun `banco vazio migra tudo`() {
        subir()
        assertThat(versaoAtual()).isEqualTo("35")
    }

    companion object {
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
