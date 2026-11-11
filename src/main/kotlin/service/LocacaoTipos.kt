package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.ClienteEntity
import org.example.model.EstadoDevolucao
import org.example.model.PedidoItemEntity
import org.example.model.PedidoLinhaEntity
import org.example.model.PedidoLocacaoEntity
import org.example.model.SituacaoBem
import org.example.model.SituacaoPedido
import org.example.model.TagEntity
import org.example.model.TipoManutencao
import org.example.repository.ClienteRepository
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.PedidoItemRepository
import org.example.repository.PedidoLinhaRepository
import org.example.repository.PedidoLocacaoRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.util.normalizarEpc
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// Tipos da locação (o que vem da tela e o que as consultas devolvem). Ficavam no topo do LocacaoService até a 1.18.1.

/** Por que um equipamento lido não serve ao pedido. */
enum class ProblemaLeitura(val rotulo: String) {
    SEM_CADASTRO("Etiqueta sem cadastro"),
    FORA_DO_PEDIDO("Não faz parte do pedido"),
    NAO_DISPONIVEL("Não está disponível"),
    EXCEDENTE("O pedido já tem a quantidade deste item")
}

/** Um equipamento lido (ou digitado) conferido contra o pedido. */
data class ItemConferido(
    val codigo: String,
    val tag: TagEntity?,
    val linhaId: Long?,
    val jaSaiu: Boolean,
    val problema: ProblemaLeitura?
) {
    val aceito: Boolean get() = problema == null && !jaSaiu
}

/** Uma linha com a quantidade pedida, o que já saiu e o que esta leitura completaria. */
data class LinhaConferida(val linha: PedidoLinhaEntity, val saidos: Int, val conferidos: Int) {
    val faltam: Int get() = (linha.quantidade - saidos).coerceAtLeast(0)
}

data class Conferencia(val itens: List<ItemConferido>, val linhas: List<LinhaConferida>)

data class ResultadoSaida(val conferencia: Conferencia, val aceitos: List<ItemConferido>, val pedido: PedidoLocacaoEntity) {
    val recusados: List<ItemConferido> get() = conferencia.itens.filter { it.problema != null }
    /** Aceitos agora mais os que já tinham saído (reenvio): o app conta todos como "saíram". */
    val saidos: List<ItemConferido> get() = conferencia.itens.filter { it.problema == null }
}

/** Devolução de equipamentos lidos: recebidos (com o pedido), os sem locação, e os pedidos tocados. */
data class ResultadoDevolucao(
    val recebidos: List<Pair<PedidoItemEntity, PedidoLocacaoEntity>>,
    val semLocacao: List<Pair<String, TagEntity?>>,
    val pedidos: List<PedidoLocacaoEntity>
)

data class PedidoResumo(val pedido: PedidoLocacaoEntity, val quantidade: Int, val saidos: Int, val naRua: Int, val diasAtraso: Long) {
    val atrasado: Boolean get() = diasAtraso > 0
}

data class PedidoDetalhe(
    val pedido: PedidoLocacaoEntity,
    val linhas: List<LinhaConferida>,
    val itens: List<PedidoItemEntity>,
    val diasAtraso: Long,
    /** 1.16.0: as mudanças da devolução prevista, mais antigas primeiro. */
    val prorrogacoes: List<org.example.model.PedidoProrrogacaoEntity> = emptyList(),
    /** 1.18.0: os acessórios do pedido (do kit das linhas e avulsos). */
    val acessorios: List<org.example.model.PedidoAcessorioEntity> = emptyList()
) {
    /** Acessórios enviados que ainda não voltaram. */
    val acessoriosFaltando: Int get() = acessorios.sumOf { it.faltando }
    val acessoriosAEnviar: Int get() = acessorios.sumOf { it.aEnviar }

    /** A descrição da linha que trouxe o acessório pelo kit (nulo: avulso). */
    fun linhaDoAcessorio(linhaId: Long?): String? = linhaId?.let { id -> linhas.firstOrNull { it.linha.id == id }?.linha?.descricao }

    val naRua: List<PedidoItemEntity> get() = itens.filter { !it.devolvido }
    val aguardandoConferencia: List<PedidoItemEntity> get() = itens.filter { it.aguardandoConferencia }
    val atrasado: Boolean get() = diasAtraso > 0
    val podeSair: Boolean get() = !pedido.situacao.finalizado && !pedido.separacaoConcluida
    val editavel: Boolean get() = !pedido.situacao.finalizado
}

/** Uma linha da folha de separação: o que falta e os equipamentos disponíveis que servem (1.17.0). */
data class LinhaSeparacao(val linha: LinhaConferida, val candidatos: List<BemTransferivel>)

data class FolhaSeparacao(val detalhe: PedidoDetalhe, val linhas: List<LinhaSeparacao>) {
    val faltam: Int get() = linhas.sumOf { it.linha.faltam }
}

/** Filtro da lista de pedidos. */
enum class FiltroPedido(val rotulo: String, val situacoes: Set<SituacaoPedido>) {
    ANDAMENTO("Em aberto e na rua", setOf(SituacaoPedido.ABERTO, SituacaoPedido.EM_SEPARACAO, SituacaoPedido.ENTREGUE, SituacaoPedido.DEVOLVIDO_PARCIAL)),
    SEPARAR("A separar", setOf(SituacaoPedido.ABERTO, SituacaoPedido.EM_SEPARACAO)),
    NA_RUA("Na rua", setOf(SituacaoPedido.EM_SEPARACAO, SituacaoPedido.ENTREGUE, SituacaoPedido.DEVOLVIDO_PARCIAL)),
    ATRASADOS("Atrasados", SituacaoPedido.entries.toSet()),
    ENCERRADOS("Encerrados e cancelados", setOf(SituacaoPedido.ENCERRADO, SituacaoPedido.CANCELADO)),
    TODOS("Todos", SituacaoPedido.entries.toSet());

    companion object {
        fun de(texto: String?): FiltroPedido = entries.firstOrNull { it.name.equals(texto?.trim(), ignoreCase = true) } ?: ANDAMENTO
    }
}

/** Dados do cliente como vêm da tela. */
data class DadosCliente(
    val nome: String?, val documento: String? = null, val contato: String? = null, val telefone: String? = null,
    val email: String? = null, val endereco: String? = null, val observacao: String? = null
)

/** Cabeçalho do pedido como vem da tela. */
data class DadosPedido(
    val clienteId: Long?, val previsaoDevolucao: LocalDate?, val saidaPrevista: LocalDate? = null,
    val localEntrega: String? = null, val observacao: String? = null
)

