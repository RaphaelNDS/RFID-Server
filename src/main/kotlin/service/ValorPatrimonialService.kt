package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.TagEntity
import org.example.model.TipoEquipamentoEntity
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.response.TagViewAdmin
import org.example.util.Depreciacao
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

/** Um tipo de equipamento com a regra de depreciação e o que ele soma hoje. */
data class TipoComDepreciacao(
    val tipo: TipoEquipamentoEntity,
    val bens: Int,
    val comValor: Int,
    val valorAquisicao: BigDecimal,
    val valorContabil: BigDecimal
)

/** Um bem no relatório de valores. [depreciacao] null = sem valor de aquisição. */
data class LinhaValor(
    val bem: TagViewAdmin,
    val dataAquisicao: LocalDate?,
    val notaFiscal: String?,
    val depreciacao: Depreciacao?
)

/** Totais de um grupo (tipo, local ou o relatório inteiro), só dos bens com valor. */
data class TotalValor(
    val nome: String,
    val bens: Int,
    val comValor: Int,
    val valorAquisicao: BigDecimal,
    val depreciacaoAcumulada: BigDecimal,
    val valorContabil: BigDecimal
) {
    val semValor: Int get() = bens - comValor
}

data class FiltroValores(
    val unidadeId: Long? = null,
    val localId: Long? = null,
    val tipoId: Long? = null,
    val incluirBaixados: Boolean = false
)

data class RelatorioValores(
    val referencia: LocalDate,
    val filtro: FiltroValores,
    val linhas: List<LinhaValor>,
    val porTipo: List<TotalValor>,
    val porLocal: List<TotalValor>,
    val total: TotalValor
)

/**
 * Valor patrimonial (1.8.0): a regra de depreciação de cada tipo de equipamento (vida útil e residual), o valor
 * contábil de um bem e o relatório de valores por tipo e por local. O cálculo é o de [Depreciacao].
 */
@Service
class ValorPatrimonialService(
    private val tags: TagRepository,
    private val tipos: TipoEquipamentoRepository,
    private val rfid: RfidService,
    private val cadastros: PatrimonioCadastrosService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun hoje(): LocalDate = LocalDate.now(relogio)

    /** Valor contábil do bem hoje (ou na [referencia]); null quando ele não tem valor de aquisição. */
    @Transactional(readOnly = true)
    fun depreciacaoDe(tag: TagEntity, referencia: LocalDate = hoje()): Depreciacao? {
        val tipo = tag.tipoEquipamentoId?.let { tipos.findById(it).orElse(null) }
        return calcular(tag, tipo, referencia)
    }

    private fun calcular(tag: TagEntity, tipo: TipoEquipamentoEntity?, referencia: LocalDate): Depreciacao? =
        Depreciacao.calcular(tag.valorAquisicao, tag.dataAquisicao, tipo?.vidaUtilMeses, tipo?.valorResidualPct, referencia,
            baixaEm = if (tag.baixado) tag.baixaEm ?: referencia else null)

    /** Valor contábil hoje de cada etiqueta (EPC) que tem valor de aquisição; as sem valor ficam fora do mapa. */
    @Transactional(readOnly = true)
    fun valoresDe(etiquetas: Collection<String>, referencia: LocalDate = hoje()): Map<String, Depreciacao> {
        if (etiquetas.isEmpty()) return emptyMap()
        val tiposPorId = tipos.findAll().associateBy { it.id }
        // Em blocos: o IN do PostgreSQL tem limite de parâmetros
        return etiquetas.distinct().chunked(1_000).flatMap { tags.findAllByTagRealIn(it) }
            .mapNotNull { t -> calcular(t, t.tipoEquipamentoId?.let { tiposPorId[it] }, referencia)?.let { t.tagReal to it } }
            .toMap()
    }

    /** Os tipos, pelo nome, com quantos bens têm e quanto somam hoje (bens baixados ficam de fora). */
    @Transactional(readOnly = true)
    fun tipos(): List<TipoComDepreciacao> {
        val hoje = hoje()
        val porTipo = tags.findAll().filter { !it.baixado }.groupBy { it.tipoEquipamentoId }
        return tipos.findAll().sortedBy { it.nome.lowercase() }.map { t ->
            val bens = porTipo[t.id].orEmpty()
            val calculados = bens.mapNotNull { calcular(it, t, hoje) }
            TipoComDepreciacao(t, bens.size, calculados.size,
                calculados.fold(BigDecimal.ZERO) { s, d -> s + d.valorAquisicao },
                calculados.fold(BigDecimal.ZERO) { s, d -> s + d.valorContabil })
        }
    }

    /**
     * Grava a vida útil (meses, 1 a 1200; vazio = não deprecia) e o residual (0 a 100%; vazio = 0) do tipo. Vai para a
     * auditoria com o antes e o depois.
     */
    @Transactional
    fun salvarRegra(tipoId: Long, vidaUtil: String?, residual: String?): TipoEquipamentoEntity {
        val tipo = tipos.findById(tipoId).orElseThrow { NoSuchElementException("Tipo de equipamento $tipoId não encontrado") }
        val vida = vidaUtil?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull()?.takeIf { v -> v in 1..1200 } ?: throw IllegalArgumentException("Vida útil inválida: $it (de 1 a 1200 meses)")
        }
        val pct = residual?.trim()?.removeSuffix("%")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.replace(',', '.').toBigDecimalOrNull()?.takeIf { v -> v >= BigDecimal.ZERO && v <= BigDecimal(100) }
                ?.setScale(2, java.math.RoundingMode.HALF_UP)
                ?: throw IllegalArgumentException("Valor residual inválido: $it (de 0 a 100%)")
        }
        require(pct == null || vida != null) { "Informe a vida útil para usar o valor residual" }
        val antes = descricaoRegra(tipo.vidaUtilMeses, tipo.valorResidualPct)
        tipo.vidaUtilMeses = vida
        tipo.valorResidualPct = pct
        tipos.save(tipo)
        val depois = descricaoRegra(vida, pct)
        if (antes != depois) {
            auditoria.registrar(AreaAuditoria.CATALOGO, "Depreciação do tipo alterada", tipo.nome, "$antes → $depois")
            log.info("Depreciação do tipo {} alterada: {} → {}", tipo.nome, antes, depois)
        }
        return tipo
    }

    /** O relatório de valores: os bens (pelo local e pelo patrimônio) e os totais por tipo, por local e geral. */
    @Transactional(readOnly = true)
    fun relatorio(filtro: FiltroValores, referencia: LocalDate = hoje()): RelatorioValores {
        val nomeUnidade = filtro.unidadeId?.let { id -> cadastros.listarUnidades().firstOrNull { it.id == id }?.nome }
        val vistas = rfid.listarTodasParaAdmin(null, FiltroEtiquetas(localId = filtro.localId, incluirBaixados = filtro.incluirBaixados))
            .filter { filtro.unidadeId == null || it.unidadeNome == nomeUnidade }
        val entidades = tags.findAllById(vistas.map { it.id }).associateBy { it.id }
        val tiposPorId = tipos.findAll().associateBy { it.id }
        val linhas = vistas.mapNotNull { v ->
            val tag = entidades[v.id] ?: return@mapNotNull null
            if (filtro.tipoId != null && tag.tipoEquipamentoId != filtro.tipoId) return@mapNotNull null
            LinhaValor(v, tag.dataAquisicao, tag.notaFiscal, calcular(tag, tag.tipoEquipamentoId?.let { tiposPorId[it] }, referencia))
        }.sortedWith(compareBy<LinhaValor>({ it.bem.localDescricao?.lowercase() ?: "￿" }, { it.bem.patrimonio.ifBlank { "￿" } }, { it.bem.tag }))

        fun total(nome: String, grupo: List<LinhaValor>): TotalValor {
            val d = grupo.mapNotNull { it.depreciacao }
            return TotalValor(nome, grupo.size, d.size,
                d.fold(BigDecimal.ZERO) { s, x -> s + x.valorAquisicao },
                d.fold(BigDecimal.ZERO) { s, x -> s + x.depreciacaoAcumulada },
                d.fold(BigDecimal.ZERO) { s, x -> s + x.valorContabil })
        }
        val porTipo = linhas.groupBy { it.bem.tipoNome ?: "Sem tipo" }.map { (n, g) -> total(n, g) }.sortedBy { it.nome.lowercase() }
        val porLocal = linhas.groupBy { it.bem.localDescricao ?: "Sem local" }.map { (n, g) -> total(n, g) }.sortedBy { it.nome.lowercase() }
        log.debug("Relatório de valores: {} bem(ns), filtro {}", linhas.size, filtro)
        return RelatorioValores(referencia, filtro, linhas, porTipo, porLocal, total("Total", linhas))
    }

    /** Descrição do filtro para o cabeçalho do relatório: "Unidade Matriz · Local Sala 101 · Tipo Notebook". */
    fun descricaoFiltro(filtro: FiltroValores): String = listOfNotNull(
        filtro.unidadeId?.let { id -> cadastros.listarUnidades().firstOrNull { it.id == id }?.let { "Unidade ${it.nome}" } },
        filtro.localId?.let { id -> cadastros.opcoesDeLocal(incluirInativos = true).firstOrNull { it.id == id }?.let { "Local ${it.descricao}" } },
        filtro.tipoId?.let { id -> tipos.findById(id).orElse(null)?.let { "Tipo ${it.nome}" } },
        "com os baixados".takeIf { filtro.incluirBaixados }
    ).joinToString(" · ").ifEmpty { "Todos os bens (sem os baixados)" }

    private fun descricaoRegra(vida: Int?, pct: BigDecimal?): String =
        if (vida == null) "não deprecia" else "$vida meses, residual ${(pct ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString()}%"

}
