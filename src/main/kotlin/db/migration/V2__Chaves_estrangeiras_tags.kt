package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Liga as etiquetas ao catálogo com chaves estrangeiras: o banco passa a recusar etiqueta apontando para
 * tipo/marca/modelo inexistente e a exclusão de um item do catálogo que ainda está em uso.
 *
 * Em Kotlin (e não SQL) para poder registrar no log as etiquetas afetadas: se o banco já tiver
 * referências quebradas (de exclusões antigas, quando não havia essa proteção), elas são zeradas antes de
 * criar as chaves. Sem essa limpeza a migração falharia e o servidor não subiria.
 */
@Suppress("ClassName") // o Flyway exige o padrão V<versão>__<descrição>
class V2__Chaves_estrangeiras_tags : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Referencia(val coluna: String, val tabela: String, val descricao: String, val constraint: String)

    private val referencias = listOf(
        Referencia("tipo_equipamento_id", "tipo_equipamento", "tipo de equipamento", "fk_tags_tipo_equipamento"),
        Referencia("marca_id", "marca", "marca", "fk_tags_marca"),
        Referencia("modelo_id", "modelo", "modelo", "fk_tags_modelo")
    )

    override fun migrate(context: Context) {
        val conexao = context.connection

        referencias.forEach { ref ->
            limparReferenciasQuebradas(conexao, ref)

            conexao.createStatement().use {
                it.execute(
                    "alter table tags add constraint ${ref.constraint} " +
                        "foreign key (${ref.coluna}) references ${ref.tabela} (id)"
                )
            }
        }

        log.info("Etiquetas ligadas ao catálogo: o banco agora impede excluir tipo, marca ou modelo em uso")
    }

    private fun limparReferenciasQuebradas(conexao: Connection, ref: Referencia) {
        val filtro = "${ref.coluna} is not null and ${ref.coluna} not in (select id from ${ref.tabela})"

        val afetadas = mutableListOf<String>()
        conexao.createStatement().use { st ->
            st.executeQuery("select tag_real, ${ref.coluna} from tags where $filtro").use { rs ->
                while (rs.next()) afetadas += "${rs.getString(1)} (id ${rs.getLong(2)})"
            }
        }

        if (afetadas.isEmpty()) return

        log.warn(
            "{} etiqueta(s) apontavam para {} que não existe mais e ficaram sem {}: {}. Revise o cadastro delas.",
            afetadas.size, ref.descricao, ref.descricao, afetadas.joinToString()
        )

        conexao.createStatement().use { it.executeUpdate("update tags set ${ref.coluna} = null where $filtro") }
    }
}
