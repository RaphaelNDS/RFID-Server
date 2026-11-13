package org.example.service

import org.example.repository.UserRepository
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** O que se conta por pessoa (a ordem é a das colunas da tela). */
enum class AtividadeOperador(val rotulo: String, val dica: String) {
    LEITURAS("Leituras", "Leituras de etiquetas cadastradas pelo coletor (fora do inventário)"),
    INVENTARIOS("Inventários", "Inventários abertos no coletor (os de treino não contam)"),
    ITENS_INVENTARIO("Etiquetas em inventário", "Etiquetas lidas dentro dos inventários"),
    CADASTROS("Cadastros", "Etiquetas cadastradas (na web ou no coletor)"),
    SAIDAS("Saídas", "Equipamentos que saíram nos pedidos de locação"),
    DEVOLUCOES("Devoluções", "Equipamentos devolvidos"),
    CONFERENCIAS("Conferências", "Devoluções conferidas"),
    ENTREGAS("Entregas", "Comprovantes de entrega registrados"),
    TRANSFERENCIAS("Transferências", "Transferências de local registradas"),
    MANUTENCOES("Manutenções", "Ordens de manutenção abertas")
}

data class LinhaOperador(val pessoa: String, val contagens: Map<AtividadeOperador, Long>, val dias: Int) {
    fun de(a: AtividadeOperador): Long = contagens[a] ?: 0
    val total: Long get() = contagens.values.sum()
}

data class RelatorioOperadores(
    val inicio: LocalDate,
    val fim: LocalDate,
    val linhas: List<LinhaOperador>,
    /** Por dia (mais recente primeiro): pessoa → total de ações. */
    val porDia: List<Pair<LocalDate, Map<String, Long>>>
) {
    fun total(a: AtividadeOperador): Long = linhas.sumOf { it.de(a) }
    val total: Long get() = linhas.sumOf { it.total }
}

/**
 * Relatório por operador (1.32.0): quem fez o quê no período, juntando o que o sistema já guarda com o nome de quem
 * fez (leituras, inventários, cadastros, saídas, devoluções, conferências, entregas, transferências e manutenções). Na
 * web o registro guarda o email; no coletor, o nome: o email vira o nome do usuário, para a mesma pessoa somar junto.
 */
@Service
class RelatorioOperadoresService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val usuarios: UserRepository
) {

    /** Cada atividade: a tabela, a coluna de quem fez e a coluna de quando (com um filtro extra opcional). */
    private data class Fonte(val atividade: AtividadeOperador, val tabela: String, val quem: String, val quando: String, val filtro: String = "")

    private val fontes = listOf(
        Fonte(AtividadeOperador.LEITURAS, "leituras_android", "operador", "data_hora"),
        Fonte(AtividadeOperador.INVENTARIOS, "inventarios", "operador", "iniciado_em", "and not treino"),
        Fonte(AtividadeOperador.ITENS_INVENTARIO, "inventario_itens", "operador", "lido_em",
            "and inventario_id in (select id from inventarios where not treino)"),
        Fonte(AtividadeOperador.CADASTROS, "historico_etiquetas", "usuario", "momento", "and acao = 'CADASTRO'"),
        Fonte(AtividadeOperador.SAIDAS, "pedido_itens", "saida_por", "saida_em"),
        Fonte(AtividadeOperador.DEVOLUCOES, "pedido_itens", "devolvido_por", "devolvido_em"),
        Fonte(AtividadeOperador.CONFERENCIAS, "pedido_itens", "conferido_por", "conferido_em"),
        Fonte(AtividadeOperador.ENTREGAS, "entregas_pedido", "registrada_por", "registrada_em"),
        Fonte(AtividadeOperador.TRANSFERENCIAS, "transferencias", "registrada_por", "criada_em"),
        Fonte(AtividadeOperador.MANUTENCOES, "manutencoes", "registrada_por", "criada_em")
    )

    @Transactional(readOnly = true)
    fun relatorio(inicio: LocalDate, fim: LocalDate): RelatorioOperadores {
        require(!fim.isBefore(inicio)) { "O fim do período vem antes do início" }
        require(fim.toEpochDay() - inicio.toEpochDay() <= 366) { "Escolha um período de até um ano" }
        val params = MapSqlParameterSource().addValue("inicio", inicio.atStartOfDay()).addValue("fim", fim.plusDays(1).atStartOfDay())

        // (atividade, pessoa como foi gravada, dia) → quantidade
        val sql = fontes.joinToString("\nunion all\n") { f ->
            "select '${f.atividade.name}' as atividade, ${f.quem} as quem, cast(${f.quando} as date) as dia, count(*) as qtd " +
                "from ${f.tabela} where ${f.quem} is not null and ${f.quando} >= :inicio and ${f.quando} < :fim ${f.filtro} " +
                "group by ${f.quem}, cast(${f.quando} as date)"
        }
        data class Registro(val atividade: AtividadeOperador, val pessoa: String, val dia: LocalDate, val qtd: Long)
        val nomes = usuarios.findAll().associate { it.email.lowercase() to it.nome }
        val registros = jdbc.query(sql, params) { rs, _ ->
            val quem = rs.getString("quem").trim()
            Registro(AtividadeOperador.valueOf(rs.getString("atividade")), nomes[quem.lowercase()] ?: quem,
                rs.getDate("dia").toLocalDate(), rs.getLong("qtd"))
        }.filter { it.pessoa.isNotEmpty() && it.pessoa != "sistema" }

        val linhas = registros.groupBy { it.pessoa }.map { (pessoa, rs) ->
            LinhaOperador(pessoa, rs.groupBy { it.atividade }.mapValues { (_, l) -> l.sumOf { it.qtd } }, rs.map { it.dia }.distinct().size)
        }.sortedWith(compareByDescending<LinhaOperador> { it.total }.thenBy { it.pessoa.lowercase() })
        val porDia = registros.groupBy { it.dia }.toSortedMap(compareByDescending { it })
            .map { (dia, rs) -> dia to rs.groupBy { it.pessoa }.mapValues { (_, l) -> l.sumOf { it.qtd } } }
        return RelatorioOperadores(inicio, fim, linhas, porDia)
    }
}
