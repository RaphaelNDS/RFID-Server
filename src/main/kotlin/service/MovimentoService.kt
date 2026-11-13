package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.MovimentoEntity
import org.example.model.MovimentoItemEntity
import org.example.model.OrigemMovimento
import org.example.model.SituacaoBem
import org.example.model.TagEntity
import org.example.model.TipoMovimento
import org.example.repository.MovimentoItemRepository
import org.example.repository.MovimentoRepository
import org.example.repository.TagRepository
import org.example.util.normalizarEpc
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** Uma saída ou entrada a registrar: os equipamentos por EPC ou patrimônio (web) ou só por EPC (coletor). */
data class NovoMovimento(
    val tipo: TipoMovimento,
    val identificadores: List<String>,
    val destino: String? = null,
    val observacao: String? = null,
    val origem: OrigemMovimento = OrigemMovimento.WEB,
    /** Quando aconteceu (a hora do coletor, já conferida); null = agora. */
    val momento: LocalDateTime? = null,
    /** Quem registrou (email na web; operador e coletor no coletor). */
    val registradoPor: String
)

/** O resultado de um equipamento informado: aceito, ou recusado com o [motivo]. */
data class ResultadoItemMovimento(
    val identificador: String,
    val aceito: Boolean,
    val motivo: String? = null,
    val epc: String? = null,
    val patrimonio: String? = null,
    val descricao: String? = null
)

/** O resultado: a movimentação gravada (null se nenhum equipamento foi aceito) e o de cada equipamento. */
data class ResultadoMovimento(val movimento: MovimentoEntity?, val itens: List<ResultadoItemMovimento>) {
    val aceitos: List<ResultadoItemMovimento> get() = itens.filter { it.aceito }
    val recusados: List<ResultadoItemMovimento> get() = itens.filter { !it.aceito }
}

/** Uma movimentação com os equipamentos (tela de detalhe, comprovante e lista). */
data class MovimentoDetalhe(val movimento: MovimentoEntity, val itens: List<MovimentoItemEntity>)

/** Filtros da tela Movimentações (vazios = tudo). */
data class FiltroMovimentos(val tipo: TipoMovimento? = null, val de: LocalDate? = null, val ate: LocalDate? = null) {
    val ativo: Boolean get() = tipo != null || de != null || ate != null
}

/**
 * Saída e entrada de equipamentos (2.0.0). A saída leva equipamentos que estão no estoque (DISPONIVEL) para fora (FORA),
 * com o destino; a entrada traz de volta os que estão fora. Recusa o que já está fora (na saída), o que já está no
 * estoque (na entrada) e os baixados e extraviados. Cada equipamento movimentado entra no histórico da etiqueta
 * (campo Situação, com o número da movimentação) e a movimentação vai para a auditoria.
 *
 * Na web é tudo ou nada ([registrar] com tudoOuNada): um equipamento recusado devolve a lista de problemas e nada é
 * gravado. No coletor, os aceitos são movimentados e os recusados voltam com o motivo, um a um.
 */
@Service
class MovimentoService(
    private val movimentos: MovimentoRepository,
    private val itens: MovimentoItemRepository,
    private val tags: TagRepository,
    private val rfid: RfidService,
    private val historico: HistoricoEtiquetaService,
    private val auditoria: AuditoriaService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun registrar(novo: NovoMovimento, tudoOuNada: Boolean): ResultadoMovimento {
        val destino = novo.destino?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
        val observacao = novo.observacao?.trim()?.takeIf { it.isNotEmpty() }
        if (novo.tipo == TipoMovimento.SAIDA) require(destino != null) { "Informe o destino da saída (para onde os equipamentos vão)" }
        require(destino == null || destino.length <= 255) { "O destino pode ter no máximo 255 caracteres" }
        require(observacao == null || observacao.length <= 500) { "A observação pode ter no máximo 500 caracteres" }

        // Repetidos na lista (o mesmo EPC lido duas vezes, a mesma linha colada de novo) contam uma vez
        val identificadores = novo.identificadores.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.uppercase() }
        require(identificadores.isNotEmpty()) { "Informe ao menos um equipamento (patrimônio ou EPC)" }
        require(identificadores.size <= MAXIMO_ITENS) { "No máximo $MAXIMO_ITENS equipamentos por movimentação (vieram ${identificadores.size})" }

        val porColetor = novo.origem == OrigemMovimento.COLETOR
        val encontrados = identificadores.associateWith { localizar(it, soEpc = porColetor) }
        val jaNestaLista = mutableSetOf<Long>()
        val avaliados = identificadores.map { id ->
            val achado = encontrados.getValue(id)
            val tag = achado.tag
            val motivo = achado.erro ?: tag?.let { motivoRecusa(it, novo.tipo) }
                ?: if (!jaNestaLista.add(tag!!.id)) "Repetido na lista (o mesmo equipamento por EPC e por patrimônio)" else null
            id to (tag to motivo)
        }

        val nomes = rfid.carregarParaLote(avaliados.mapNotNull { it.second.first?.tagReal }).second
        fun descricao(t: TagEntity) = listOfNotNull(t.tipoEquipamentoId?.let { nomes.tipos[it] }, t.marcaId?.let { nomes.marcas[it] },
            t.modeloId?.let { nomes.modelos[it] }).joinToString(" ").ifBlank { null }

        val resultados = avaliados.map { (id, par) ->
            val (tag, motivo) = par
            ResultadoItemMovimento(id, motivo == null, motivo, tag?.tagReal, tag?.patrimonio?.ifBlank { null }, tag?.let(::descricao))
        }
        val aceitos = avaliados.filter { it.second.second == null }.map { it.second.first!! }

        if (aceitos.isEmpty() || (tudoOuNada && resultados.any { !it.aceito })) {
            log.warn("{} de {} equipamento(s) recusada para {} ({}): {}", novo.tipo.rotulo, identificadores.size, novo.registradoPor,
                novo.origem.rotulo, resultados.filter { !it.aceito }.joinToString("; ") { "${it.identificador}: ${it.motivo}" })
            return ResultadoMovimento(null, resultados)
        }

        val agora = LocalDateTime.now(relogio)
        val movimento = movimentos.save(MovimentoEntity(
            tipo = novo.tipo, dataHora = novo.momento ?: agora, destino = destino, observacao = observacao,
            registradoPor = novo.registradoPor.take(255), origem = novo.origem, criadoEm = agora
        ))
        val novaSituacao = if (novo.tipo == TipoMovimento.SAIDA) SituacaoBem.FORA else SituacaoBem.DISPONIVEL
        val motivoHistorico = "${novo.tipo.rotulo} nº ${movimento.id}" + (destino?.let { " (destino: $it)" } ?: "")
        aceitos.forEach { tag ->
            itens.save(MovimentoItemEntity(movimentoId = movimento.id, tagId = tag.id, tagReal = tag.tagReal,
                patrimonio = tag.patrimonio.ifBlank { null }, descricao = descricao(tag)?.take(255)))
            val antes = historico.retrato(tag)
            tag.situacao = novaSituacao
            tags.save(tag)
            historico.registrarAlteracao(tag, antes, motivoHistorico)
        }
        // Grava agora: duas movimentações ao mesmo tempo com o mesmo equipamento esbarram aqui (@Version da etiqueta)
        tags.flush()

        val resumo = aceitos.joinToString { it.patrimonio.ifBlank { it.tagReal } }
        auditoria.registrar(AreaAuditoria.MOVIMENTOS, "${novo.tipo.rotulo} de equipamentos", "Nº ${movimento.id}",
            listOfNotNull("${aceitos.size} equipamento(s): $resumo", destino?.let { "destino: $it" }, observacao?.let { "obs.: $it" },
                resultados.count { !it.aceito }.takeIf { it > 0 }?.let { "$it recusado(s)" }).joinToString(" · "))
        log.info("{} nº {} registrada por {} ({}): {} equipamento(s){}{}", novo.tipo.rotulo, movimento.id, novo.registradoPor,
            novo.origem.rotulo, aceitos.size, destino?.let { ", destino '$it'" } ?: "",
            resultados.filter { !it.aceito }.takeIf { it.isNotEmpty() }
                ?.joinToString("; ", prefix = "; recusados: ") { "${it.identificador} (${it.motivo})" } ?: "")
        return ResultadoMovimento(movimento, resultados)
    }

    private data class Achado(val tag: TagEntity? = null, val erro: String? = null)

    /** Pelo EPC; na web, também pelo patrimônio (o EPC vale antes). */
    private fun localizar(identificador: String, soEpc: Boolean): Achado {
        tags.findByTagReal(normalizarEpc(identificador)).orElse(null)?.let { return Achado(it) }
        if (soEpc) return Achado(erro = "Etiqueta não cadastrada")
        val porPatrimonio = tags.findAllByPatrimonio(identificador)
        return when {
            porPatrimonio.size == 1 -> Achado(porPatrimonio.first())
            porPatrimonio.size > 1 -> Achado(erro = "Patrimônio em ${porPatrimonio.size} etiquetas: informe o EPC")
            else -> Achado(erro = "Não cadastrado (nenhuma etiqueta com este EPC ou patrimônio)")
        }
    }

    /** Por que o equipamento não pode ter esta movimentação, ou null se pode. */
    private fun motivoRecusa(tag: TagEntity, tipo: TipoMovimento): String? = when {
        tag.situacao == SituacaoBem.BAIXADO -> "Baixado: não pode ser movimentado (desfaça a baixa antes)"
        tag.situacao == SituacaoBem.EXTRAVIADO -> "Extraviado: não pode ser movimentado (corrija a situação na edição da etiqueta)"
        tipo == TipoMovimento.SAIDA && tag.situacao == SituacaoBem.FORA ->
            "Já está fora do estoque" + (ultimaSaida(tag.id)?.let { " (saída nº ${it.id} para ${it.destino})" } ?: "")
        tipo == TipoMovimento.ENTRADA && tag.situacao == SituacaoBem.DISPONIVEL -> "Já está no estoque"
        else -> null
    }

    /** A saída que levou o equipamento (a mais recente), para "Fora do estoque desde". */
    fun ultimaSaida(tagId: Long): MovimentoEntity? =
        itens.ultimosDoTipo(tagId, TipoMovimento.SAIDA, PageRequest.of(0, 1)).firstOrNull()

    // ================= CONSULTA =================

    @Transactional(readOnly = true)
    fun listar(filtro: FiltroMovimentos, busca: String?, pagina: Int): Page<MovimentoDetalhe> {
        val de = filtro.de?.atStartOfDay() ?: LocalDateTime.of(1900, 1, 1, 0, 0)
        val ate = filtro.ate?.plusDays(1)?.atStartOfDay() ?: LocalDateTime.of(9999, 1, 1, 0, 0)
        val tipos = filtro.tipo?.let { listOf(it) } ?: TipoMovimento.entries
        val ordem = Sort.by(Sort.Direction.DESC, "dataHora").and(Sort.by(Sort.Direction.DESC, "id"))
        val resultado = RfidService.paginaValida(pagina) { p ->
            movimentos.buscar(tipos, de, ate, RfidService.padraoBusca(busca), PageRequest.of(p, RfidService.TAMANHO_PAGINA, ordem))
        }
        return comItens(resultado)
    }

    @Transactional(readOnly = true)
    fun ultimos(): List<MovimentoDetalhe> {
        val lista = movimentos.findTop5ByOrderByDataHoraDescIdDesc()
        val porMovimento = if (lista.isEmpty()) emptyMap() else itens.findAllByMovimentoIdIn(lista.map { it.id }).groupBy { it.movimentoId }
        return lista.map { MovimentoDetalhe(it, porMovimento[it.id].orEmpty()) }
    }

    @Transactional(readOnly = true)
    fun detalhar(id: Long): MovimentoDetalhe {
        val movimento = movimentos.findById(id).orElseThrow { NoSuchElementException("Movimentação nº $id não encontrada") }
        return MovimentoDetalhe(movimento, itens.findAllByMovimentoIdOrderByIdAsc(id))
    }

    private fun comItens(pagina: Page<MovimentoEntity>): Page<MovimentoDetalhe> {
        val porMovimento = if (pagina.isEmpty) emptyMap()
        else itens.findAllByMovimentoIdIn(pagina.content.map { it.id }).groupBy { it.movimentoId }
        return pagina.map { MovimentoDetalhe(it, porMovimento[it.id].orEmpty().sortedBy { i -> i.id }) }
    }

    companion object {
        /** Equipamentos por movimentação (web e coletor). */
        const val MAXIMO_ITENS = 1000
    }
}
