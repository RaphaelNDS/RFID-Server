package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.ManutencaoEntity
import org.example.model.ResultadoManutencao
import org.example.model.SituacaoBem
import org.example.model.StatusManutencao
import org.example.model.TipoManutencao
import org.example.repository.ManutencaoRepository
import org.example.repository.TagRepository
import org.example.util.FormatoBr
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** O que a tela de nova manutenção pede. [codigo]: etiqueta (EPC) ou patrimônio do bem. */
data class PedidoManutencao(
    val codigo: String?,
    val tipo: TipoManutencao?,
    val problema: String?,
    val fornecedor: String? = null,
    val dataEnvio: LocalDate? = null,
    val previsaoRetorno: LocalDate? = null
)

/** O que a tela pede para concluir. [custo] como a pessoa digitou ("1.250,00"). */
data class ConclusaoManutencao(
    val resultado: ResultadoManutencao?,
    val servicoRealizado: String?,
    val custo: String?,
    val data: LocalDate? = null
)

enum class FiltroManutencao(val rotulo: String) {
    ABERTAS("Abertas"),
    ATRASADAS("Atrasadas"),
    ENCERRADAS("Concluídas e canceladas"),
    TODAS("Todas");

    companion object {
        fun de(texto: String?): FiltroManutencao = entries.firstOrNull { it.name.equals(texto?.trim(), ignoreCase = true) } ?: ABERTAS
    }
}

/** Uma ordem com os dias de atraso (0 = no prazo, sem previsão ou encerrada). */
data class ManutencaoResumo(val manutencao: ManutencaoEntity, val diasAtraso: Long) {
    val atrasada: Boolean get() = diasAtraso > 0
}

/** As manutenções de um bem, para a edição da etiqueta: a aberta (se houver), todas e o custo somado. */
data class ManutencoesDoBem(val aberta: ManutencaoResumo?, val todas: List<ManutencaoEntity>, val custoTotal: BigDecimal)

/**
 * Manutenção do bem (1.11.0): ordem de serviço numerada, uma por bem. Na abertura o bem fica "Em manutenção"; ao
 * concluir (com o serviço, o custo e o resultado) ou cancelar, volta para a situação anterior.
 *
 * Regras:
 *  - só vai para manutenção equipamento "Disponível" ou "À venda" (não alugado, extraviado, baixado nem já em manutenção);
 *  - enquanto a ordem está aberta, o bem não recebe baixa, não é excluído e a situação não muda pela edição;
 *  - "Sem conserto" também devolve a situação anterior: a baixa (sucata) é uma decisão à parte, pela tela da baixa;
 *  - tudo vai para o histórico do bem ("Manutenção nº X") e para a auditoria (Patrimônio).
 */
@Service
class ManutencaoService(
    private val manutencoes: ManutencaoRepository,
    private val tags: TagRepository,
    private val transferencias: TransferenciaService,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun hoje(): LocalDate = LocalDate.now(relogio)

    @Transactional(readOnly = true)
    fun localizar(codigo: String): BensLocalizados = transferencias.localizar(listOf(codigo))

    @Transactional
    fun abrir(pedido: PedidoManutencao): ManutencaoEntity {
        val codigo = pedido.codigo?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Informe o bem (etiqueta ou patrimônio)")
        val localizados = localizar(codigo)
        require(localizados.ambiguos.isEmpty()) { "Patrimônio de mais de um bem (use a etiqueta): $codigo" }
        val bem = localizados.bens.singleOrNull() ?: throw IllegalArgumentException("Não encontrado no cadastro: $codigo")
        val tipo = pedido.tipo ?: throw IllegalArgumentException("Escolha o tipo de manutenção")
        val problema = texto(pedido.problema, "O problema", 500) ?: throw IllegalArgumentException(
            if (tipo == TipoManutencao.PREVENTIVA) "Descreva o que será revisado" else "Descreva o problema")
        val fornecedor = texto(pedido.fornecedor, "O fornecedor", 160)
        val hoje = hoje()
        val envio = pedido.dataEnvio ?: hoje
        require(!envio.isAfter(hoje)) { "A data de envio não pode ser no futuro" }
        pedido.previsaoRetorno?.let { require(!it.isBefore(envio)) { "A previsão de retorno não pode ser antes do envio" } }

        val tag = tags.findById(bem.id).orElseThrow()
        require(tag.situacao in PODEM_IR) { "Só vai para manutenção equipamento Disponível ou À venda: este está ${tag.situacao.rotulo}" }

        val usuario = historico.autor().first
        val ordem = manutencoes.save(ManutencaoEntity(
            tagId = tag.id, tagReal = tag.tagReal, patrimonio = bem.patrimonio, descricaoBem = bem.descricao?.take(255),
            tipo = tipo, problema = problema, fornecedor = fornecedor, dataEnvio = envio, previsaoRetorno = pedido.previsaoRetorno,
            situacaoAnterior = tag.situacao, registradaPor = usuario
        ))
        val antes = historico.retrato(tag)
        tag.situacao = SituacaoBem.EM_MANUTENCAO
        tags.save(tag)
        historico.registrarAlteracao(tag, antes, "Manutenção nº ${ordem.id}")
        // Grava já: o índice único (uma manutenção aberta por bem) recusa aqui se outra tela abriu ao mesmo tempo
        manutencoes.flush()

        log.info("Manutenção nº {} aberta por {}: bem {} ({}), {}{}", ordem.id, usuario, rotulo(ordem), tipo.rotulo,
            problema, fornecedor?.let { "; fornecedor: $it" } ?: "")
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Manutenção aberta", "Manutenção nº ${ordem.id}",
            "${rotulo(ordem)}: ${tipo.rotulo}" + (fornecedor?.let { ", $it" } ?: ""))
        return ordem
    }

    @Transactional
    fun concluir(id: Long, conclusao: ConclusaoManutencao): ManutencaoEntity {
        val ordem = aberta(id)
        val resultado = conclusao.resultado ?: throw IllegalArgumentException("Escolha o resultado")
        val servico = texto(conclusao.servicoRealizado, "O serviço realizado", 500)
        val custo = FormatoBr.lerValor(conclusao.custo)
        val dia = dataDeEncerramento(ordem, conclusao.data, "conclusão")

        val usuario = historico.autor().first
        ordem.status = StatusManutencao.CONCLUIDA
        ordem.resultado = resultado
        ordem.servicoRealizado = servico
        ordem.custo = custo
        ordem.concluidaEm = dia
        ordem.concluidaPor = usuario
        manutencoes.save(ordem)
        devolverSituacao(ordem, "Manutenção nº $id concluída")

        log.info("Manutenção nº {} concluída por {}: bem {}, {}{}", id, usuario, rotulo(ordem), resultado.rotulo,
            custo?.let { ", custo ${FormatoBr.reais(it)}" } ?: "")
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Manutenção concluída", "Manutenção nº $id",
            "${rotulo(ordem)}: ${resultado.rotulo}" + (custo?.let { ", ${FormatoBr.reais(it)}" } ?: ""))
        return ordem
    }

    /** Ordem aberta por engano (ou o bem nem chegou a ir): o bem volta como estava. */
    @Transactional
    fun cancelar(id: Long, motivo: String?): ManutencaoEntity {
        val ordem = aberta(id)
        val obs = texto(motivo, "O motivo", 500) ?: throw IllegalArgumentException("Informe o motivo do cancelamento")
        val usuario = historico.autor().first
        ordem.status = StatusManutencao.CANCELADA
        ordem.servicoRealizado = "Cancelada: $obs".take(500)
        ordem.concluidaEm = hoje()
        ordem.concluidaPor = usuario
        manutencoes.save(ordem)
        devolverSituacao(ordem, "Manutenção nº $id cancelada")
        log.warn("Manutenção nº {} cancelada por {}: bem {} ({})", id, usuario, rotulo(ordem), obs)
        auditoria.registrar(AreaAuditoria.PATRIMONIO, "Manutenção cancelada", "Manutenção nº $id", "${rotulo(ordem)}: $obs")
        return ordem
    }

    @Transactional(readOnly = true)
    fun listar(filtro: FiltroManutencao, busca: String?, pagina: Int): Page<ManutencaoResumo> {
        val hoje = hoje()
        val texto = busca?.trim().orEmpty().take(255)
        val nome = if (filtro == FiltroManutencao.TODAS) "" else filtro.name
        return RfidService.paginaValida(pagina) {
            manutencoes.buscar(texto, nome, hoje, StatusManutencao.ABERTA, PageRequest.of(it, POR_PAGINA))
        }.map { ManutencaoResumo(it, atraso(it, hoje)) }
    }

    @Transactional(readOnly = true)
    fun detalhar(id: Long): ManutencaoResumo {
        val ordem = manutencoes.findById(id).orElseThrow { NoSuchElementException("Manutenção nº $id não encontrada") }
        return ManutencaoResumo(ordem, atraso(ordem, hoje()))
    }

    @Transactional(readOnly = true)
    fun doBem(tagId: Long): ManutencoesDoBem {
        val todas = manutencoes.findAllByTagIdOrderByDataEnvioDescIdDesc(tagId)
        val hoje = hoje()
        return ManutencoesDoBem(
            todas.firstOrNull { it.aberta }?.let { ManutencaoResumo(it, atraso(it, hoje)) },
            todas,
            todas.mapNotNull { it.custo }.fold(BigDecimal.ZERO, BigDecimal::add)
        )
    }

    /** Número da manutenção aberta do bem, ou null (travas do RfidService). */
    fun abertaDoBem(tagId: Long): Long? = manutencoes.findFirstByTagIdAndStatus(tagId, StatusManutencao.ABERTA)?.id

    @Transactional(readOnly = true)
    fun contarAtrasadas(): Long = manutencoes.contarAtrasadas(hoje(), StatusManutencao.ABERTA)

    private fun aberta(id: Long): ManutencaoEntity {
        val ordem = manutencoes.findById(id).orElseThrow { NoSuchElementException("Manutenção nº $id não encontrada") }
        require(ordem.aberta) { "A manutenção nº $id já está ${ordem.status.rotulo.lowercase()}" }
        return ordem
    }

    /** Volta o bem para a situação de antes, se ele ainda estiver "Em manutenção" (se alguém mudou, fica). */
    private fun devolverSituacao(ordem: ManutencaoEntity, motivo: String) {
        val tag = ordem.tagId?.let { tags.findById(it).orElse(null) } ?: return
        if (tag.situacao != SituacaoBem.EM_MANUTENCAO) return
        val antes = historico.retrato(tag)
        tag.situacao = ordem.situacaoAnterior
        tags.save(tag)
        historico.registrarAlteracao(tag, antes, motivo)
    }

    private fun dataDeEncerramento(ordem: ManutencaoEntity, data: LocalDate?, campo: String): LocalDate {
        val hoje = hoje()
        val dia = data ?: hoje
        require(!dia.isAfter(hoje)) { "A data de $campo não pode ser no futuro" }
        require(!dia.isBefore(ordem.dataEnvio)) { "A data de $campo não pode ser antes do envio (${FormatoBr.data(ordem.dataEnvio)})" }
        return dia
    }

    private fun atraso(m: ManutencaoEntity, hoje: LocalDate): Long =
        if (!m.aberta || m.previsaoRetorno == null) 0 else ChronoUnit.DAYS.between(m.previsaoRetorno, hoje).coerceAtLeast(0)

    private fun rotulo(m: ManutencaoEntity) = m.patrimonio ?: m.tagReal

    private fun texto(valor: String?, campo: String, maximo: Int): String? =
        valor?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= maximo) { "$campo pode ter no máximo $maximo caracteres" } }

    companion object {
        const val POR_PAGINA = 50
        val PODEM_IR = setOf(SituacaoBem.DISPONIVEL, SituacaoBem.A_VENDA)
    }
}
