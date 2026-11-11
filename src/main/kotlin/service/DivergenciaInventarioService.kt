package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.InventarioEntity
import org.example.model.MotivoBaixa
import org.example.model.SituacaoBem
import org.example.model.StatusInventario
import org.example.repository.InventarioRepository
import org.example.repository.LocalRepository
import org.example.repository.TagRepository
import org.example.util.normalizarEpc
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Ação sobre os bens que faltaram num inventário. A transferência tem tela própria (TransferenciaService). */
enum class AcaoDivergencia { EXTRAVIADO, BAIXA }

data class ResultadoDivergencia(val tratados: Int, val ignorados: List<String>)

/**
 * Ação sobre as divergências de um inventário FINALIZADO (1.6.1): os bens que faltaram podem ser marcados como
 * extraviados, baixados ou transferidos (para onde foram achados). Só vale para os bens que faltaram NESTE inventário
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
    private val transferencias: TransferenciaService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Os bens que faltaram como estão AGORA no cadastro (situação, local e responsável), pela etiqueta. */
    @Transactional(readOnly = true)
    fun situacaoAtual(etiquetas: Collection<String>): Map<String, BemTransferivel> =
        if (etiquetas.isEmpty()) emptyMap()
        else transferencias.descrever(etiquetas.distinct().chunked(5_000).flatMap { tags.findAllByTagRealIn(it) }).associateBy { it.tag }

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
        // Já baixado: não há o que fazer. Já extraviado: marcar de novo não muda nada. Emprestado (1.10.0) ou em
        // manutenção (1.11.0): faltou porque está fora; a volta é pela tela do empréstimo ou da manutenção
        val ignorados = bens.filter {
            it.baixado || it.situacao == SituacaoBem.EMPRESTADO || it.situacao == SituacaoBem.EM_MANUTENCAO ||
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
}
