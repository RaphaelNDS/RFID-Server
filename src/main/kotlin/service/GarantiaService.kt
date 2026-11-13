package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.TagEntity
import org.example.repository.TagRepository
import org.example.util.FormatoBr
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Garantia do fabricante (1.26.0). Cada equipamento pode ter até quando vai a garantia e com quem (fabricante ou
 * fornecedor). A ordem de manutenção aberta dentro da garantia fica marcada "Na garantia" (ManutencaoService.abrir),
 * e a tela de Manutenções lista as garantias que vencem nos próximos dias, para acionar antes de vencer.
 */
@Service
class GarantiaService(
    private val tags: TagRepository,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun hoje(): LocalDate = LocalDate.now(relogio)

    /** Grava a garantia; a data vazia apaga (e o fornecedor junto). Cada mudança vai para o histórico da etiqueta. */
    @Transactional
    fun definir(tagId: Long, ate: LocalDate?, fornecedor: String?): TagEntity {
        val tag = tags.findById(tagId).orElseThrow { NoSuchElementException("Etiqueta $tagId não encontrada") }
        val f = fornecedor?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 160) { "O fabricante ou fornecedor pode ter no máximo 160 caracteres" } }
        require(ate != null || f == null) { "Informe até quando vai a garantia" }
        ate?.let { require(it.year in 1990..2100) { "Data da garantia inválida" } }
        val novoFornecedor = if (ate == null) null else f
        if (tag.garantiaAte == ate && tag.garantiaFornecedor == novoFornecedor) return tag
        if (tag.garantiaAte != ate) historico.registrarCampo(tag, "Garantia até", tag.garantiaAte?.let(FormatoBr::data), ate?.let(FormatoBr::data))
        if (tag.garantiaFornecedor != novoFornecedor) historico.registrarCampo(tag, "Garantia com", tag.garantiaFornecedor, novoFornecedor)
        tag.garantiaAte = ate
        tag.garantiaFornecedor = novoFornecedor
        tags.save(tag)
        val rotulo = tag.patrimonio.ifBlank { tag.tagReal }
        val usuario = historico.autor().first
        val resumo = ate?.let { "até ${FormatoBr.data(it)}" + (novoFornecedor?.let { n -> " com $n" } ?: "") } ?: "sem garantia"
        log.info("Garantia do equipamento {} definida por {}: {}", rotulo, usuario, resumo)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Garantia do fabricante", rotulo, resumo)
        return tag
    }

    /** Os equipamentos do acervo cuja garantia vence de hoje até [dias] dias, a mais próxima primeiro. */
    @Transactional(readOnly = true)
    fun vencendo(dias: Long = 30): List<TagEntity> {
        val hoje = hoje()
        val limite = hoje.plusDays(dias)
        return tags.findAll().filter { t -> !t.foraDoAcervo && t.garantiaAte?.let { !it.isBefore(hoje) && !it.isAfter(limite) } == true }
            .sortedBy { it.garantiaAte }
    }

    /** O bem ([tagId]) está na garantia hoje? Para o aviso na manutenção nova. */
    @Transactional(readOnly = true)
    fun naGarantia(tagId: Long): TagEntity? = tags.findById(tagId).orElse(null)?.takeIf { it.naGarantia(hoje()) }
}
