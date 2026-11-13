package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.InventarioEntity
import org.example.model.MotivoBaixa
import org.example.model.SituacaoBem
import org.example.model.StatusInventario
import org.example.repository.InventarioRepository
import org.example.model.TagEntity
import org.example.repository.LocalRepository
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.repository.UnidadeRepository
import org.example.repository.TagRepository
import org.example.util.normalizarEpc
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Ação sobre os bens que faltaram num inventário. */
enum class AcaoDivergencia { EXTRAVIADO, BAIXA }

data class ResultadoDivergencia(val tratados: Int, val ignorados: List<String>)

/** Um bem que faltou, como está agora no cadastro (tela do inventário). */
data class BemAtual(
    val id: Long,
    val tag: String,
    val patrimonio: String?,
    val codigoInterno: String?,
    val descricao: String?,
    val local: String?,
    val situacao: SituacaoBem
)

/**
 * Ação sobre as divergências de um inventário FINALIZADO (1.6.1): os bens que faltaram podem ser marcados como
 * extraviados ou baixados. Só vale para os bens que faltaram NESTE inventário
 * (a lista vem do EsperadoInventarioService, a mesma da tela), para um formulário adulterado não mexer em outro bem.
 * No histórico de cada bem, a origem diz de que inventário saiu a ação.
 */
@Service
class DivergenciaInventarioService(
    private val inventarios: InventarioRepository,
    private val locais: LocalRepository,
    private val tags: TagRepository,
    private val esperados: EsperadoInventarioService,
    private val rfid: RfidService,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val tipos: TipoEquipamentoRepository,
    private val marcas: MarcaRepository,
    private val modelos: ModeloRepository,
    private val unidades: UnidadeRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Os bens que faltaram como estão AGORA no cadastro (situação e local), pela etiqueta. */
    @Transactional(readOnly = true)
    fun situacaoAtual(etiquetas: Collection<String>): Map<String, BemAtual> =
        if (etiquetas.isEmpty()) emptyMap()
        else descrever(etiquetas.distinct().chunked(5_000).flatMap { tags.findAllByTagRealIn(it) }).associateBy { it.tag }

    /**
     * As etiquetas escolhidas que faltaram neste inventário. Inventário em andamento é recusado: a lista ainda muda
     * conforme o coletor envia as leituras.
     */
    @Transactional(readOnly = true)
    fun faltaramNoInventario(inventarioId: String, escolhidas: Collection<String>): Pair<InventarioEntity, List<String>> {
        val inventario = inventarios.findById(inventarioId).orElseThrow { NoSuchElementException("Inventário $inventarioId não encontrado") }
        require(inventario.status == StatusInventario.FINALIZADO) { "O inventário ainda não foi finalizado: a lista do que faltou ainda pode mudar" }
        val pedidas = escolhidas.map { normalizarEpc(it) }.filter { it.isNotEmpty() }.distinct()
        require(pedidas.isNotEmpty()) { "Marque ao menos um bem da lista" }
        val faltaram = esperados.faltandoNoInventario(inventarioId).faltando.map { it.tag }.toSet()
        val fora = pedidas.filter { it !in faltaram }
        require(fora.isEmpty()) { "Não faltou neste inventário: ${fora.take(5).joinToString(", ")}" }
        return inventario to pedidas
    }

    @Transactional
    fun tratar(
        inventarioId: String, escolhidas: Collection<String>, acao: AcaoDivergencia,
        motivoBaixa: MotivoBaixa? = null, data: LocalDate? = null, observacao: String? = null
    ): ResultadoDivergencia {
        val (inventario, etiquetas) = faltaramNoInventario(inventarioId, escolhidas)
        if (acao == AcaoDivergencia.BAIXA) requireNotNull(motivoBaixa) { "Escolha o motivo da baixa" }
        val nomeLocal = locais.findById(inventario.localId).map { it.nome }.orElse("?")
        val origem = "Inventário de $nomeLocal (${inventario.iniciadoEm.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))})"

        val bens = etiquetas.chunked(5_000).flatMap { tags.findAllByTagRealIn(it) }
        // Já baixado: não há o que fazer. Já extraviado: marcar de novo não muda nada. Fora do estoque: faltou porque
        // saiu (Movimentações); a volta é pela entrada
        val ignorados = bens.filter {
            it.foraDoAcervo || it.situacao == SituacaoBem.FORA ||
                (acao == AcaoDivergencia.EXTRAVIADO && it.situacao == SituacaoBem.EXTRAVIADO)
        }
        val tratar = bens - ignorados.toSet()
        tratar.forEach { tag ->
            when (acao) {
                AcaoDivergencia.EXTRAVIADO -> {
                    val antes = historico.retrato(tag)
                    tag.situacao = SituacaoBem.EXTRAVIADO
                    tags.save(tag)
                    historico.registrarAlteracao(tag, antes, origem)
                }
                AcaoDivergencia.BAIXA -> rfid.darBaixa(tag.id, motivoBaixa!!, data, observacao, origem)
            }
        }

        val descricao = when (acao) {
            AcaoDivergencia.EXTRAVIADO -> "marcado(s) como extraviado(s)"
            AcaoDivergencia.BAIXA -> "baixado(s) (${motivoBaixa!!.rotulo})"
        }
        log.info("Divergências do inventário {} ('{}'): {} bem(ns) {} por {}{}", inventarioId, nomeLocal, tratar.size, descricao,
            historico.autor().first, if (ignorados.isNotEmpty()) "; ${ignorados.size} já estava(m) assim e ficaram como estão" else "")
        if (tratar.isNotEmpty()) {
            auditoria.registrar(AreaAuditoria.PATRIMONIO, "Divergências do inventário", "$nomeLocal ($inventarioId)", "${tratar.size} bem(ns) $descricao")
        }
        return ResultadoDivergencia(tratar.size, ignorados.map { it.patrimonio.ifBlank { it.tagReal } })
    }

    /** Os bens com os nomes do catálogo e o local ("Matriz › Sala 101"), por patrimônio. */
    private fun descrever(lista: List<TagEntity>): List<BemAtual> {
        if (lista.isEmpty()) return emptyList()
        val nomesTipo = tipos.findAllById(lista.mapNotNull { it.tipoEquipamentoId }.toSet()).associate { it.id to it.nome }
        val nomesMarca = marcas.findAllById(lista.mapNotNull { it.marcaId }.toSet()).associate { it.id to it.nome }
        val nomesModelo = modelos.findAllById(lista.mapNotNull { it.modeloId }.toSet()).associate { it.id to it.nome }
        val nomesUnidade = unidades.findAll().associate { it.id to it.nome }
        val nomesLocal = locais.findAllById(lista.mapNotNull { it.localId }.toSet())
            .associate { l -> l.id to (l.unidadeId?.let(nomesUnidade::get)?.let { "$it › ${l.nome}" } ?: l.nome) }
        return lista.map { t ->
            BemAtual(
                id = t.id, tag = t.tagReal, patrimonio = t.patrimonio.ifBlank { null }, codigoInterno = t.codigoInterno.ifBlank { null },
                descricao = listOfNotNull(t.tipoEquipamentoId?.let(nomesTipo::get), t.marcaId?.let(nomesMarca::get), t.modeloId?.let(nomesModelo::get))
                    .joinToString(" ").ifBlank { null },
                local = t.localId?.let(nomesLocal::get), situacao = t.situacao
            )
        }
    }
}
