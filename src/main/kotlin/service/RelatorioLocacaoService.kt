package org.example.service

import org.example.model.AcaoHistorico
import org.example.model.EstadoDevolucao
import org.example.model.SituacaoBem
import org.example.model.StatusManutencao
import org.example.model.TagEntity
import org.example.repository.HistoricoEtiquetaRepository
import org.example.repository.ManutencaoRepository
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.PedidoItemRepository
import org.example.repository.PedidoLocacaoRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Uso de um modelo no período: quantos há, quantos dias ficaram alugados e a ocupação. */
data class UsoModelo(val modelo: String, val equipamentos: Int, val locacoes: Int, val diasAlugado: Double, val diasPeriodo: Long) {
    /** % do tempo em que os equipamentos do modelo ficaram com clientes no período. */
    val ocupacao: Double get() = if (equipamentos == 0 || diasPeriodo == 0L) 0.0 else (diasAlugado / (equipamentos * diasPeriodo) * 100).coerceAtMost(100.0)
}

/** Equipamento disponível parado: desde quando não sai (ou nunca saiu). */
data class EquipamentoParado(val tagId: Long, val patrimonio: String, val modelo: String, val ultimaLocacao: LocalDate?, val diasParado: Long?)

/** Avarias na devolução e manutenção de um modelo no período. */
data class AvariasModelo(val modelo: String, val devolucoes: Int, val avarias: Int, val faltandoAcessorio: Int, val manutencoes: Int, val custo: BigDecimal) {
    val taxaAvaria: Double get() = if (devolucoes == 0) 0.0 else (avarias + faltandoAcessorio) * 100.0 / devolucoes
}

/** Pontualidade de um cliente: devoluções no período, quantas atrasaram, a média do atraso e o que está atrasado agora. */
data class AtrasoCliente(val cliente: String, val clienteId: Long, val devolucoes: Int, val atrasadas: Int, val mediaDiasAtraso: Double, val atrasadosAgora: Int) {
    val taxaAtraso: Double get() = if (devolucoes == 0) 0.0 else atrasadas * 100.0 / devolucoes
}

data class RelatorioLocacao(
    val de: LocalDate,
    val ate: LocalDate,
    val diasParadoMinimo: Long,
    val uso: List<UsoModelo>,
    val parados: List<EquipamentoParado>,
    val avarias: List<AvariasModelo>,
    val atrasos: List<AtrasoCliente>
) {
    val diasPeriodo: Long get() = ChronoUnit.DAYS.between(de, ate) + 1
}

/**
 * Relatórios internos da locação (1.17.0): uso e ocupação por modelo, equipamentos disponíveis parados, avarias e
 * manutenção por modelo e pontualidade dos clientes, num período. Só leitura, a partir dos pedidos, das manutenções e
 * do cadastro.
 */
@Service
class RelatorioLocacaoService(
    private val tags: TagRepository,
    private val itens: PedidoItemRepository,
    private val pedidos: PedidoLocacaoRepository,
    private val manutencoes: ManutencaoRepository,
    private val historico: HistoricoEtiquetaRepository,
    private val tipos: TipoEquipamentoRepository,
    private val marcas: MarcaRepository,
    private val modelos: ModeloRepository,
    private val relogio: Clock
) {

    fun hoje(): LocalDate = LocalDate.now(relogio)

    @Transactional(readOnly = true)
    fun relatorio(de: LocalDate?, ate: LocalDate?, diasParadoMinimo: Long = 60): RelatorioLocacao {
        val hoje = hoje()
        val fim = (ate ?: hoje).coerceAtMost(hoje)
        val inicio = (de ?: fim.minusDays(89)).coerceAtMost(fim)
        require(ChronoUnit.DAYS.between(inicio, fim) <= 3 * 366) { "O período pode ter no máximo 3 anos" }
        val agora = LocalDateTime.now(relogio)
        val inicioPeriodo = inicio.atStartOfDay()
        val fimPeriodo = fim.plusDays(1).atStartOfDay().coerceAtMost(agora)
        val diasPeriodo = ChronoUnit.DAYS.between(inicio, fim) + 1

        val acervo = tags.findAll().filter { !it.foraDoAcervo }
        val porId = tags.findAll().associateBy { it.id }
        val nomes = Nomes(tipos.findAll().associate { it.id to it.nome }, marcas.findAll().associate { it.id to it.nome }, modelos.findAll().associate { it.id to it.nome })
        val todosItens = itens.findAll()
        val ped = pedidos.findAll().associateBy { it.id }

        // Uso por modelo: os dias de cada saída que caem dentro do período
        val itensNoPeriodo = todosItens.filter { it.saidaEm.isBefore(fimPeriodo) && (it.devolvidoEm ?: agora).isAfter(inicioPeriodo) }
        val diasPorModelo = itensNoPeriodo.groupBy { nomes.de(it.tagId?.let(porId::get)) }.mapValues { (_, lista) ->
            lista.sumOf { i ->
                val ini = maxOf(i.saidaEm, inicioPeriodo)
                val fimItem = minOf(i.devolvidoEm ?: agora, fimPeriodo)
                Duration.between(ini, fimItem).toMinutes().coerceAtLeast(0) / 1440.0
            } to lista.map { it.pedidoId }.distinct().size
        }
        val equipamentosPorModelo = acervo.groupingBy { nomes.de(it) }.eachCount()
        val uso = (equipamentosPorModelo.keys + diasPorModelo.keys).distinct().map { m ->
            val (dias, locacoes) = diasPorModelo[m] ?: (0.0 to 0)
            UsoModelo(m, equipamentosPorModelo[m] ?: 0, locacoes, Math.round(dias * 10) / 10.0, diasPeriodo)
        }.sortedWith(compareByDescending<UsoModelo> { it.ocupacao }.thenBy { it.modelo })

        // Parados: Disponíveis sem saída há N dias (pela última devolução ou, se nunca saiu, pelo cadastro)
        val ultimaVolta = todosItens.filter { it.tagId != null }.groupBy { it.tagId!! }
            .mapValues { (_, lista) -> lista.maxOf { (it.devolvidoEm ?: agora) } }
        val parados = acervo.filter { it.situacao == SituacaoBem.DISPONIVEL }.mapNotNull { t ->
            val volta = ultimaVolta[t.id]
            val desde = volta ?: cadastroDe(t)
            val dias = desde?.let { ChronoUnit.DAYS.between(it.toLocalDate(), hoje) }
            if (dias != null && dias < diasParadoMinimo) return@mapNotNull null
            EquipamentoParado(t.id, t.patrimonio.ifBlank { t.tagReal }, nomes.de(t), volta?.toLocalDate(), dias)
        }.sortedWith(compareByDescending<EquipamentoParado> { it.ultimaLocacao == null }.thenByDescending { it.diasParado ?: Long.MAX_VALUE })

        // Avarias (devoluções conferidas no período) e manutenções abertas no período, por modelo
        val conferidos = todosItens.filter { c -> c.conferidoEm?.let { !it.isBefore(inicioPeriodo) && it.isBefore(fimPeriodo) } == true }
        val ordens = manutencoes.findAll().filter { it.status != StatusManutencao.CANCELADA && !it.dataEnvio.isBefore(inicio) && !it.dataEnvio.isAfter(fim) }
        val modelosAvaria = (conferidos.map { nomes.de(it.tagId?.let(porId::get)) } + ordens.map { nomes.de(it.tagId?.let(porId::get)) }).distinct()
        val avarias = modelosAvaria.map { m ->
            val c = conferidos.filter { nomes.de(it.tagId?.let(porId::get)) == m }
            val o = ordens.filter { nomes.de(it.tagId?.let(porId::get)) == m }
            AvariasModelo(m, c.size, c.count { it.estadoDevolucao == EstadoDevolucao.AVARIA }, c.count { it.estadoDevolucao == EstadoDevolucao.FALTANDO_ACESSORIO },
                o.size, o.mapNotNull { it.custo }.fold(BigDecimal.ZERO, BigDecimal::add))
        }.filter { it.devolucoes > 0 || it.manutencoes > 0 }
            .sortedWith(compareByDescending<AvariasModelo> { it.avarias + it.faltandoAcessorio }.thenByDescending { it.custo })

        // Pontualidade: devoluções no período (pela data prevista do pedido, já com as prorrogações) e atrasados agora
        val devolvidos = todosItens.filter { d -> d.devolvidoEm?.let { !it.isBefore(inicioPeriodo) && it.isBefore(fimPeriodo) } == true }
        val naRua = todosItens.filter { !it.devolvido }
        val clientes = (devolvidos + naRua).mapNotNull { ped[it.pedidoId] }.map { it.clienteId to it.cliente }.distinct()
        val atrasos = clientes.map { (id, nome) ->
            val dev = devolvidos.filter { ped[it.pedidoId]?.clienteId == id }
            val atrasosDias = dev.mapNotNull { i -> ped[i.pedidoId]?.let { ChronoUnit.DAYS.between(it.previsaoDevolucao, i.devolvidoEm!!.toLocalDate()) }?.takeIf { it > 0 } }
            val agoraAtrasados = naRua.count { i -> ped[i.pedidoId]?.let { it.clienteId == id && it.previsaoDevolucao.isBefore(hoje) } == true }
            AtrasoCliente(nome, id, dev.size, atrasosDias.size, if (atrasosDias.isEmpty()) 0.0 else Math.round(atrasosDias.average() * 10) / 10.0, agoraAtrasados)
        }.filter { it.devolucoes > 0 || it.atrasadosAgora > 0 }
            .sortedWith(compareByDescending<AtrasoCliente> { it.atrasadosAgora }.thenByDescending { it.taxaAtraso }.thenBy { it.cliente })

        return RelatorioLocacao(inicio, fim, diasParadoMinimo, uso, parados, avarias, atrasos)
    }

    /** Quando o equipamento foi cadastrado (pelo histórico), para quem nunca saiu. */
    private fun cadastroDe(t: TagEntity): LocalDateTime? =
        historico.findByTagRealOrderByMomentoDescIdDesc(t.tagReal).lastOrNull { it.acao == AcaoHistorico.CADASTRO }?.momento

    /** "Coletor Zebra MC3300", "Coletor (sem marca)", "Sem tipo". */
    private class Nomes(val tipos: Map<Long, String>, val marcas: Map<Long, String>, val modelos: Map<Long, String>) {
        fun de(t: TagEntity?): String {
            if (t == null) return "Equipamento excluído"
            val tipo = t.tipoEquipamentoId?.let(tipos::get) ?: return "Sem tipo"
            val resto = listOfNotNull(t.marcaId?.let(marcas::get), t.modeloId?.let(modelos::get)).joinToString(" ")
            return if (resto.isBlank()) "$tipo (sem marca)" else "$tipo $resto"
        }
    }
}
