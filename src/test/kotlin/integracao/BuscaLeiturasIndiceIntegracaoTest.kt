package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.suporte.IntegracaoBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** V15: os filtros "contém" da tela de leituras usam os índices trigram em vez de ler a tabela inteira. */
class BuscaLeiturasIndiceIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun `indices trigram existem nos seis campos do filtro`() {
        val indices = jdbc.queryForList(
            "select indexname from pg_indexes where tablename = 'leituras_android' and indexname like '%trgm'", String::class.java
        )
        assertThat(indices).containsExactlyInAnyOrder(
            "idx_leituras_android_tag_trgm", "idx_leituras_android_tipo_trgm", "idx_leituras_android_marca_trgm",
            "idx_leituras_android_modelo_trgm", "idx_leituras_android_serie_trgm", "idx_leituras_android_operador_trgm"
        )
    }

    @Test
    fun `busca com porcentagem no comeco usa o indice`() {
        // Tabela de teste é pequena: sem desligar a leitura sequencial o PostgreSQL nem tentaria o índice
        jdbc.execute("set local enable_seqscan = off")
        val plano = jdbc.queryForList(
            "explain select * from leituras_android where lower(tag) like '%e280%' escape '\\'", String::class.java
        ).joinToString("\n")

        assertThat(plano).contains("idx_leituras_android_tag_trgm")
    }
}
