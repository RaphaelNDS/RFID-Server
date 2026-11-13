package org.example.config

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.slf4j.LoggerFactory

/**
 * Trava da 2.0.0: o servidor simples (branch master) NUNCA roda sobre o banco da versão completa de locação (branch
 * locadora). Conferida antes de qualquer migração (MigracoesConfig), com ou sem migração pendente:
 *  - a tabela pedidos_locacao com pelo menos um pedido (o banco foi usado pela locação);
 *  - uma migração aplicada depois da [VERSAO] (a locadora continuou numerando, e o Flyway ignoraria as "do futuro");
 *  - uma V34 aplicada com outra descrição (a V34 da locadora não é esta).
 * Qualquer um deles: lança [IllegalStateException], nada é migrado e o servidor não sobe.
 */
open class TravaVersaoCompleta {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Uma migração já aplicada no banco: versão (null nas repetíveis) e descrição. */
    data class Aplicada(val versao: MigrationVersion?, val descricao: String)

    open fun conferir(flyway: Flyway) {
        val aplicadas = flyway.info().applied().map { Aplicada(it.version, it.description) }
        val pedidos = pedidosDeLocacao(flyway)
        motivo(aplicadas, pedidos)?.let { recusar(it) }
    }

    private fun recusar(motivo: String): Nothing {
        val mensagem = "Este banco é da versão completa (branch locadora): $motivo. O servidor 2.0.0 (entrada e saída de " +
            "equipamentos) não roda sobre ele e NADA foi migrado. Use o jar da locadora com este banco, ou aponte este " +
            "servidor para outro banco."
        log.error(mensagem)
        throw IllegalStateException(mensagem)
    }

    /**
     * Quantos pedidos de locação o banco tem (0 sem a tabela, como num banco novo ou de antes da 1.12.0). Conta só até
     * 1: basta saber se há algum.
     */
    private fun pedidosDeLocacao(flyway: Flyway): Long {
        val schema = flyway.configuration.schemas.firstOrNull()
        flyway.configuration.dataSource.connection.use { c ->
            val existe = c.prepareStatement(
                "select 1 from information_schema.tables where table_name = 'pedidos_locacao' and table_schema = coalesce(?, current_schema())"
            ).use { ps ->
                ps.setString(1, schema)
                ps.executeQuery().use { it.next() }
            }
            if (!existe) return 0
            val tabela = if (schema != null) "\"${schema.replace("\"", "\"\"")}\".pedidos_locacao" else "pedidos_locacao"
            return c.createStatement().use { st ->
                st.executeQuery("select count(*) from (select 1 from $tabela limit 1) p").use { rs -> rs.next(); rs.getLong(1) }
            }
        }
    }

    companion object {
        /** A migração desta versão: a última que o servidor simples conhece. */
        const val VERSAO = "34"
        const val DESCRICAO = "movimentacao simples"

        private val V34: MigrationVersion = MigrationVersion.fromVersion(VERSAO)

        /** Por que o banco é da versão completa, ou null se este servidor pode usá-lo. */
        fun motivo(aplicadas: List<Aplicada>, pedidosDeLocacao: Long): String? {
            if (pedidosDeLocacao > 0) return "a tabela pedidos_locacao tem pedidos de locação"
            aplicadas.mapNotNull { a -> a.versao?.let { it to a } }.forEach { (versao, a) ->
                if (versao > V34) return "o banco tem a migração V${versao} (${a.descricao}), posterior à V$VERSAO deste servidor"
                if (versao == V34 && a.descricao != DESCRICAO) {
                    return "a V$VERSAO aplicada no banco é \"${a.descricao}\", e não \"$DESCRICAO\""
                }
            }
            return null
        }
    }
}
