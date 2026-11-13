package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.RegraRevisaoEntity
import org.example.model.ResultadoManutencao
import org.example.model.SituacaoBem
import org.example.model.StatusManutencao
import org.example.model.TagEntity
import org.example.model.TipoManutencao
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.ManutencaoRepository
import org.example.repository.PedidoItemRepository
import org.example.repository.RegraRevisaoRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Uma regra na tela, com o nome do tipo. */
data class RegraRevisaoTela(val tipoId: Long, val tipo: String, val regra: RegraRevisaoEntity?)

/** Um equipamento com a revisão vencida e o porquê. */
data class RevisaoVencida(val tag: TagEntity, val motivo: String)

/**
 * Revisão automática por uso (1.21.0). Cada tipo de equipamento pode ter uma regra: revisar a cada N locações e/ou a
 * cada D dias, contados da última manutenção preventiva concluída como "Consertado / revisado" (ou, sem nenhuma, do
 * cadastro do bem).
 *
 *  - Na conferência da devolução, o equipamento que venceu a revisão vai direto para uma ordem de manutenção
 *    preventiva ("Revisão automática"), em vez de voltar Disponível.
 *  - Na saída, o equipamento Disponível com a revisão vencida (pelos dias, parado no estoque) é recusado.
 *  - O painel do dia lista os que estão no estoque com a revisão vencida, com o botão para abrir a revisão.
 */
@Service
class RevisaoService(
    private val regras: RegraRevisaoRepository,
    private val tipos: TipoEquipamentoRepository,
    private val tags: TagRepository,
    private val itens: PedidoItemRepository,
    private val manutencoes: ManutencaoRepository,
    private val historico: HistoricoEtiquetaRepository,
    private val historicoService: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun hoje(): LocalDate = LocalDate.now(relogio)

    /** Todos os tipos, com a regra de cada um (ou nenhuma), por nome. */
    @Transactional(readOnly = true)
    fun regrasDaTela(): List<RegraRevisaoTela> {
        val porTipo = regras.findAll().associateBy { it.tipoEquipamentoId }
        return tipos.findAll().sortedBy { it.nome.lowercase() }.map { RegraRevisaoTela(it.id, it.nome, porTipo[it.id]) }
    }

    /** Grava a regra do tipo; os dois vazios apagam a regra (o tipo fica sem revisão automática). */
    @Transactional
    fun salvarRegra(tipoId: Long, aCadaLocacoes: Int?, aCadaDias: Int?): RegraRevisaoEntity? {
        val tipo = tipos.findById(tipoId).orElseThrow { IllegalArgumentException("Tipo de equipamento $tipoId não encontrado") }
        aCadaLocacoes?.let { require(it in 1..1000) { "Locações: de 1 a 1000" } }
        aCadaDias?.let { require(it in 1..3650) { "Dias: de 1 a 3650 (10 anos)" } }
        val usuario = historicoService.autor().first
        val atual = regras.findById(tipoId).orElse(null)
        if (aCadaLocacoes == null && aCadaDias == null) {
            if (atual == null) return null
            regras.delete(atual)
            log.info("Revisão automática do tipo {} desligada por {} (era {})", tipo.nome, usuario, atual.descricao)
            auditoria.registrar(AreaAuditoria.PATRIMONIO, "Revisão automática desligada", tipo.nome, "Era ${atual.descricao}")
            return null
        }
        val regra = (atual ?: RegraRevisaoEntity(tipoEquipamentoId = tipoId, alteradaPor = usuario)).apply {
            this.aCadaLocacoes = aCadaLocacoes
            this.aCadaDias = aCadaDias
            alteradaPor = usuario
            alteradaEm = LocalDateTime.now(relogio)
        }
        regras.save(regra)
        log.info("Revisão automática do tipo {} definida por {}: {}", tipo.nome, usuario, regra.descricao)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Revisão automática", tipo.nome, regra.descricao.replaceFirstChar { it.uppercase() })
        return regra
    }

    /** Por que a revisão deste equipamento venceu, ou null (sem regra ou em dia). */
    @Transactional(readOnly = true)
    fun motivoVencida(tag: TagEntity): String? = vencidas(listOf(tag))[tag.id]

    @Transactional(readOnly = true)
    fun motivoVencidaPorId(tagId: Long): String? = tags.findById(tagId).orElse(null)?.let { motivoVencida(it) }

    /** Os equipamentos no estoque (Disponível) com a revisão vencida. */
    @Transactional(readOnly = true)
    fun vencidasNoEstoque(): List<RevisaoVencida> {
        val tiposComRegra = regras.findAll().map { it.tipoEquipamentoId }.toSet()
        if (tiposComRegra.isEmpty()) return emptyList()
        val candidatos = tags.findAll().filter { it.situacao == SituacaoBem.DISPONIVEL && it.tipoEquipamentoId in tiposComRegra }
        val motivos = vencidas(candidatos)
        return candidatos.filter { it.id in motivos }.map { RevisaoVencida(it, motivos.getValue(it.id)) }
            .sortedWith(compareBy(nullsLast()) { it.tag.patrimonio.ifBlank { null } })
    }

    /** Id da etiqueta → motivo, só para as vencidas. Poucas consultas, em lotes, para qualquer quantidade. */
    @Transactional(readOnly = true)
    fun vencidas(lista: Collection<TagEntity>): Map<Long, String> {
        val porTipo = regras.findAll().associateBy { it.tipoEquipamentoId }
        val alvo = lista.filter { it.tipoEquipamentoId != null && it.tipoEquipamentoId in porTipo }
        if (alvo.isEmpty()) return emptyMap()
        val ids = alvo.map { it.id }
        // A última revisão: as locações contam a partir da ABERTURA da ordem (o bem não sai enquanto está em manutenção,
        // e a conclusão só guarda o dia), os dias a partir da conclusão.
        val ultimaRevisao = ids.chunked(LOTE).flatMap { manutencoes.findAllByTagIdInAndTipoAndStatus(it, TipoManutencao.PREVENTIVA, StatusManutencao.CONCLUIDA) }
            .filter { it.resultado == ResultadoManutencao.CONSERTADO && it.concluidaEm != null && it.tagId != null }
            .groupBy { it.tagId!! }.mapValues { (_, l) -> l.maxWith(compareBy({ it.concluidaEm }, { it.criadaEm })) }
        val semRevisao = alvo.filter { it.id !in ultimaRevisao }.map { it.tagReal }
        val cadastro = semRevisao.chunked(LOTE).flatMap { historico.cadastros(it) }
            .associate { (it[0] as String) to (it[1] as LocalDateTime) }
        val saidas = ids.chunked(LOTE).flatMap { itens.findAllByTagIdIn(it) }.groupBy { it.tagId!! }
        val hoje = hoje()
        return alvo.mapNotNull { tag ->
            val regra = porTipo.getValue(tag.tipoEquipamentoId!!)
            val revisado = ultimaRevisao[tag.id]
            val baseLocacoes = revisado?.criadaEm ?: cadastro[tag.tagReal]
            val baseDias = revisado?.concluidaEm ?: cadastro[tag.tagReal]?.toLocalDate()
            val desde = if (revisado != null) "desde a última revisão" else "desde o cadastro"
            val locacoes = (saidas[tag.id] ?: emptyList()).count { baseLocacoes == null || it.saidaEm.isAfter(baseLocacoes) }
            val dias = baseDias?.let { ChronoUnit.DAYS.between(it, hoje) }
            val motivo = when {
                regra.aCadaLocacoes != null && locacoes >= regra.aCadaLocacoes!! -> "$locacoes locação(ões) $desde (regra: ${regra.descricao})"
                regra.aCadaDias != null && dias != null && dias >= regra.aCadaDias!! -> "$dias dias $desde (regra: ${regra.descricao})"
                else -> null
            }
            motivo?.let { tag.id to it }
        }.toMap()
    }

    private companion object {
        /** Tamanho dos lotes do IN (o driver do PostgreSQL limita os parâmetros de uma consulta). */
        const val LOTE = 1000
    }
}
