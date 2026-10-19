package org.example.migracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Simula um banco de produção antigo (só com a V1) que já tem etiquetas apontando para itens
 * excluídos do catálogo, e confere que a V2 limpa essas referências em vez de impedir o servidor de subir.
 */
class MigracaoV2Test {

    // Cada teste num schema próprio do mesmo PostgreSQL temporário: começa sempre do banco vazio
    private val schema = "migracao_v2_${System.nanoTime()}"
    private val url = postgres.jdbcUrl + (if ("?" in postgres.jdbcUrl) "&" else "?") + "currentSchema=$schema"

    private fun flyway(alvo: String? = null) = Flyway.configure()
        .dataSource(url, postgres.username, postgres.password)
        .schemas(schema)
        .locations("classpath:db/migration")
        .apply { if (alvo != null) target(alvo) }
        .load()

    private fun conectar() = DriverManager.getConnection(url, postgres.username, postgres.password)

    private fun sql(vararg comandos: String) = conectar().use { c ->
        c.createStatement().use { st -> comandos.forEach { st.execute(it) } }
    }

    private fun modeloDa(tag: String): Long? = conectar().use { c ->
        c.prepareStatement("select modelo_id from tags where tag_real = ?").use { ps ->
            ps.setString(1, tag)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1).takeUnless { rs.wasNull() } }
        }
    }

    @Test
    fun `referencias quebradas sao zeradas e as validas preservadas`() {
        flyway("1").migrate()

        sql(
            "insert into tipo_equipamento (id, nome) values (1, 'Notebook')",
            "insert into marca (id, tipo_id, nome) values (2, 1, 'Dell')",
            "insert into modelo (id, marca_id, nome) values (3, 2, 'Latitude')",
            // etiqueta íntegra
            "insert into tags (tag_hash, tag_real, codigo_interno, tipo_equipamento_id, marca_id, modelo_id) values ('h1', 'OK', 'TI-1', 1, 2, 3)",
            // etiqueta cujo modelo foi excluído no passado (possível antes da V2)
            "insert into tags (tag_hash, tag_real, codigo_interno, tipo_equipamento_id, marca_id, modelo_id) values ('h2', 'ORFA', 'TI-2', 1, 2, 999)"
        )

        val resultado = flyway().migrate()

        assertThat(resultado.success).isTrue()
        assertThat(modeloDa("OK")).isEqualTo(3)
        assertThat(modeloDa("ORFA")).isNull()
    }

    @Test
    fun `depois da V2 o banco recusa referencia inexistente`() {
        flyway().migrate()

        // O PostgreSQL guarda o nome da restrição em minúsculas
        assertThatThrownBy {
            sql("insert into tags (tag_hash, tag_real, codigo_interno, modelo_id) values ('h', 'X', 'TI', 999)")
        }.isInstanceOf(SQLException::class.java)
            .satisfies({ assertThat(it.message!!.lowercase()).contains("fk_tags_modelo") })
    }

    @Test
    fun `banco novo migra direto ate a versao atual`() {
        val resultado = flyway().migrate()

        assertThat(resultado.success).isTrue()
        assertThat(resultado.targetSchemaVersion).isEqualTo("9") // V9 = histórico do cadastro de etiquetas
    }

    companion object {
        // Um contêiner para a classe inteira (subir um por teste levaria segundos cada); mesma versão de produção
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }
}
