package org.example.service

import org.example.model.ManutencaoEntity
import org.example.model.SituacaoBem
import org.example.model.SituacaoPedido
import org.example.model.StatusManutencao
import org.example.repository.ManutencaoRepository
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.PedidoItemRepository
import org.example.repository.PedidoLinhaRepository
import org.example.repository.PedidoLocacaoRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/** Uma saída a fazer: o pedido e quantos equipamentos ainda faltam separar. */
data class SaidaDoDia(val resumo: PedidoResumo) {
    val faltam: Int get() = (resumo.quantidade - resumo.saidos).coerceAtLeast(0)
    /** Sem saída prevista, ou já passou da data: separar o quanto antes. */
    fun atrasada(hoje: LocalDate): Boolean = resumo.pedido.saidaPrevista?.isBefore(hoje) == true
}

/** O que a equipe tem para fazer hoje (tela Início, 1.13.0). */
data class PainelDoDia(
    val hoje: LocalDate,
    val porSituacao: Map<SituacaoBem, Long>,
    /** A separar: saída prevista até amanhã, já passada, ou sem data. */
    val saidas: List<SaidaDoDia>,
    val devolucoesHoje: List<PedidoResumo>,
    val atrasados: List<PedidoResumo>,
    val aConferir: Long,
    val manutencoesAbertas: Long,
    val manutencoesAtrasadas: List<ManutencaoEntity>,
    /** 1.21.0: no estoque com a revisão automática vencida. */
    val revisoesVencidas: List<RevisaoVencida> = emptyList()
) {
    fun quantos(s: SituacaoBem): Long = porSituacao[s] ?: 0
    val noAcervo: Long get() = porSituacao.filterKeys { it !in SituacaoBem.FORA_DO_ACERVO }.values.sum()
    val nadaAFazer: Boolean get() = saidas.isEmpty() && devolucoesHoje.isEmpty() && atrasados.isEmpty() && aConferir == 0L && manutencoesAtrasadas.isEmpty() && revisoesVencidas.isEmpty()
}

/** Um modelo (ou tipo/marca sem modelo) com quantos estão em cada situação. */
data class LinhaDisponibilidade(
    val marca: String?,
    val modelo: String?,
    val disponiveis: Int,
    val alugados: Int,
    val emDevolucao: Int,
    val emManutencao: Int,
    val aVenda: Int,
    val extraviados: Int,
    /** Alugados com a devolução prevista até a data escolhida. */
    val voltamAte: Int
) {
    val total: Int get() = disponiveis + alugados + emDevolucao + emManutencao + aVenda + extraviados
}

/**
 * Um tipo de equipamento: os modelos dele, os totais, e o que já está comprometido em pedidos a separar ([aSeparar]:
 * vagas abertas de pedidos que ainda não saíram por completo). [livres] = disponíveis que não estão comprometidos.
 */
data class GrupoDisponibilidade(val tipoId: Long?, val tipo: String, val linhas: List<LinhaDisponibilidade>, val aSeparar: Int) {
    val disponiveis: Int get() = linhas.sumOf { it.disponiveis }
    val alugados: Int get() = linhas.sumOf { it.alugados }
    val emDevolucao: Int get() = linhas.sumOf { it.emDevolucao }
    val emManutencao: Int get() = linhas.sumOf { it.emManutencao }
    val aVenda: Int get() = linhas.sumOf { it.aVenda }
    val extraviados: Int get() = linhas.sumOf { it.extraviados }
    val voltamAte: Int get() = linhas.sumOf { it.voltamAte }
    val total: Int get() = linhas.sumOf { it.total }
    val livres: Int get() = disponiveis - aSeparar
}

data class Disponibilidade(val ate: LocalDate, val grupos: List<GrupoDisponibilidade>) {
    val disponiveis: Int get() = grupos.sumOf { it.disponiveis }
    val alugados: Int get() = grupos.sumOf { it.alugados }
    val voltamAte: Int get() = grupos.sumOf { it.voltamAte }
    val aSeparar: Int get() = grupos.sumOf { it.aSeparar }
}

/**
 * Painel do dia e disponibilidade (1.13.0): só leitura, a partir dos pedidos de locação, das manutenções e da
 * situação dos equipamentos. Pensado para uso interno: o que separar, o que volta, o que conferir, o que está livre.
 */
@Service
class PainelService(
    private val tags: TagRepository,
    private val pedidos: PedidoLocacaoRepository,
    private val linhas: PedidoLinhaRepository,
    private val itens: PedidoItemRepository,
    private val manutencoes: ManutencaoRepository,
    private val tipos: TipoEquipamentoRepository,
    private val marcas: MarcaRepository,
    private val modelos: ModeloRepository,
    private val locacao: LocacaoService,
    private val relogio: Clock,
    // 1.21.0: revisões vencidas no estoque
    private val revisao: RevisaoService? = null
) {

    fun hoje(): LocalDate = LocalDate.now(relogio)

    @Transactional(readOnly = true)
    fun painel(): PainelDoDia {
        val hoje = hoje()
        val amanha = hoje.plusDays(1)
        val aSeparar = locacao.porSituacao(listOf(SituacaoPedido.ABERTO, SituacaoPedido.EM_SEPARACAO))
            .filter { it.quantidade > it.saidos && (it.pedido.saidaPrevista == null || !it.pedido.saidaPrevista!!.isAfter(amanha)) }
            .map(::SaidaDoDia)
        val naRua = locacao.porSituacao(listOf(SituacaoPedido.EM_SEPARACAO, SituacaoPedido.ENTREGUE, SituacaoPedido.DEVOLVIDO_PARCIAL))
            .filter { it.naRua > 0 }
        val abertas = manutencoes.findAllByStatusOrderByDataEnvioAsc(StatusManutencao.ABERTA)
        return PainelDoDia(
            hoje = hoje,
            porSituacao = tags.contarPorSituacao().associate { (it[0] as SituacaoBem) to (it[1] as Number).toLong() },
            saidas = aSeparar,
            devolucoesHoje = naRua.filter { it.pedido.previsaoDevolucao == hoje },
            atrasados = naRua.filter { it.atrasado }.sortedByDescending { it.diasAtraso },
            aConferir = locacao.contarAguardandoConferencia(),
            manutencoesAbertas = abertas.size.toLong(),
            manutencoesAtrasadas = abertas.filter { it.previsaoRetorno?.isBefore(hoje) == true },
            revisoesVencidas = revisao?.vencidasNoEstoque() ?: emptyList()
        )
    }

    /** Por tipo e modelo, o que está em cada situação e o que volta até [ate] (vazio = hoje). */
    @Transactional(readOnly = true)
    fun disponibilidade(ate: LocalDate?, tipoId: Long? = null): Disponibilidade {
        val data = ate ?: hoje()
        val acervo = tags.findAll().filter { !it.foraDoAcervo && (tipoId == null || it.tipoEquipamentoId == tipoId) }

        // Alugados que voltam até a data: o item ainda com o cliente num pedido com a devolução prevista até lá
        val naRua = itens.findAllByTagIdInAndConferidoEmIsNull(acervo.map { it.id }).filter { !it.devolvido }
        val previsoes = if (naRua.isEmpty()) emptyMap() else pedidos.findAllById(naRua.map { it.pedidoId }.toSet()).associate { it.id to it.previsaoDevolucao }
        val voltam = naRua.filter { previsoes[it.pedidoId]?.isAfter(data) == false }.mapNotNull { it.tagId }.toSet()

        // Vagas abertas nos pedidos a separar, por tipo (linha por bem específico conta no tipo do bem)
        val separar = pedidos.porSituacao(listOf(SituacaoPedido.ABERTO, SituacaoPedido.EM_SEPARACAO)).filter { !it.separacaoConcluida }
        val linhasSeparar = if (separar.isEmpty()) emptyList() else linhas.findAllByPedidoIdIn(separar.map { it.id })
        val saidosPorLinha = if (separar.isEmpty()) emptyMap() else itens.findAllByPedidoIdIn(separar.map { it.id }).groupingBy { it.linhaId }.eachCount()
        val aSepararPorTipo = linhasSeparar.groupBy { it.tipoEquipamentoId }
            .mapValues { (_, ls) -> ls.sumOf { (it.quantidade - (saidosPorLinha[it.id] ?: 0)).coerceAtLeast(0) } }

        val nomesTipo = tipos.findAll().associate { it.id to it.nome }
        val nomesMarca = marcas.findAllById(acervo.mapNotNull { it.marcaId }.toSet()).associate { it.id to it.nome }
        val nomesModelo = modelos.findAllById(acervo.mapNotNull { it.modeloId }.toSet()).associate { it.id to it.nome }

        val tiposComAlgo = (acervo.map { it.tipoEquipamentoId } + aSepararPorTipo.filterValues { it > 0 }.keys)
            .distinct().filter { tipoId == null || it == tipoId }
        val grupos = tiposComAlgo.map { t ->
            val doTipo = acervo.filter { it.tipoEquipamentoId == t }
            val porModelo = doTipo.groupBy { it.marcaId to it.modeloId }.map { (chave, lista) ->
                fun conta(s: SituacaoBem) = lista.count { it.situacao == s }
                LinhaDisponibilidade(
                    marca = chave.first?.let(nomesMarca::get), modelo = chave.second?.let(nomesModelo::get),
                    disponiveis = conta(SituacaoBem.DISPONIVEL), alugados = conta(SituacaoBem.ALUGADO), emDevolucao = conta(SituacaoBem.EM_DEVOLUCAO),
                    emManutencao = conta(SituacaoBem.EM_MANUTENCAO), aVenda = conta(SituacaoBem.A_VENDA), extraviados = conta(SituacaoBem.EXTRAVIADO),
                    voltamAte = lista.count { it.id in voltam }
                )
            }.sortedWith(compareBy<LinhaDisponibilidade, String?>(nullsLast()) { it.marca }.thenBy(nullsLast()) { it.modelo })
            GrupoDisponibilidade(t, t?.let(nomesTipo::get) ?: "Sem tipo", porModelo, aSepararPorTipo[t] ?: 0)
        }.sortedWith(compareBy<GrupoDisponibilidade> { it.tipoId == null }.thenBy { it.tipo.lowercase() })
        return Disponibilidade(data, grupos)
    }
}
