package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.SituacaoBem
import org.example.model.TagEntity
import org.example.model.VendaEntity
import org.example.repository.ClienteRepository
import org.example.repository.TagRepository
import org.example.repository.VendaRepository
import org.example.util.FormatoBr
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Venda do equipamento (1.12.0): sai Disponível ou À venda e fica Vendido (fora do acervo: some das listas e do
 * esperado dos inventários, mas continua no cadastro e no histórico). Desfazer (lançada por engano) volta para À venda.
 */
@Service
class VendaService(
    private val vendas: VendaRepository,
    private val tags: TagRepository,
    private val clientes: ClienteRepository,
    private val rfid: RfidService,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun vendaAtual(tagId: Long): VendaEntity? = vendas.findFirstByTagIdAndDesfeitaEmIsNullOrderByIdDesc(tagId)

    @Transactional
    fun vender(tagId: Long, clienteId: Long?, valor: String?, data: LocalDate?, observacao: String?): VendaEntity {
        val tag = tags.findById(tagId).orElseThrow { NoSuchElementException("Etiqueta $tagId não encontrada") }
        rfid.ocupacao(tag.id)?.let { o -> throw IllegalArgumentException("Este equipamento está ${o.onde}: ${o.acao} antes de vender") }
        require(tag.situacao in PODEM_SER_VENDIDOS) { "Só é vendido equipamento Disponível ou À venda: este está ${tag.situacao.rotulo}" }
        val cliente = clienteId?.let { id -> clientes.findById(id).orElseThrow { IllegalArgumentException("Cliente não encontrado") } }
        val hoje = LocalDate.now(relogio)
        val dia = data ?: hoje
        require(!dia.isAfter(hoje)) { "A data da venda não pode ser no futuro" }
        val obs = observacao?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 500) { "A observação pode ter no máximo 500 caracteres" } }
        val usuario = historico.autor().first
        val venda = vendas.save(VendaEntity(
            tagId = tag.id, tagReal = tag.tagReal, patrimonio = tag.patrimonio.ifBlank { null }, cliente = cliente?.descricao?.take(255),
            clienteId = cliente?.id, valor = FormatoBr.lerValor(valor), data = dia, observacao = obs, registradaPor = usuario
        ))
        mudar(tag, SituacaoBem.VENDIDO, "Venda nº ${venda.id}")
        log.info("Equipamento {} vendido por {} (venda nº {}){}{}", rotulo(tag), usuario, venda.id, cliente?.let { " para ${it.nome}" } ?: "",
            venda.valor?.let { ", ${FormatoBr.reais(it)}" } ?: "")
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Venda de equipamento", rotulo(tag),
            listOfNotNull(cliente?.nome, venda.valor?.let { FormatoBr.reais(it) }, FormatoBr.data(dia)).joinToString(", "))
        return venda
    }

    @Transactional
    fun desfazer(tagId: Long): TagEntity {
        val tag = tags.findById(tagId).orElseThrow { NoSuchElementException("Etiqueta $tagId não encontrada") }
        require(tag.situacao == SituacaoBem.VENDIDO) { "Este equipamento não está vendido" }
        val usuario = historico.autor().first
        vendaAtual(tag.id)?.let {
            it.desfeitaEm = LocalDateTime.now(relogio)
            it.desfeitaPor = usuario
            vendas.save(it)
        }
        mudar(tag, SituacaoBem.A_VENDA, "Venda desfeita")
        log.warn("Venda do equipamento {} desfeita por {}: voltou para À venda", rotulo(tag), usuario)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Venda desfeita", rotulo(tag))
        return tag
    }

    private fun mudar(tag: TagEntity, situacao: SituacaoBem, motivo: String) {
        val antes = historico.retrato(tag)
        tag.situacao = situacao
        tags.save(tag)
        historico.registrarAlteracao(tag, antes, motivo)
    }

    private fun rotulo(tag: TagEntity) = tag.patrimonio.ifBlank { tag.tagReal }

    companion object {
        val PODEM_SER_VENDIDOS = setOf(SituacaoBem.DISPONIVEL, SituacaoBem.A_VENDA)
    }
}
