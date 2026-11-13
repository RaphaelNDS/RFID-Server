package org.example.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Cliente da locadora (tabela clientes, V29). */
@Entity
@Table(name = "clientes")
class ClienteEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 160)
    var nome: String,

    /** CPF ou CNPJ, só os dígitos. */
    @Column(length = 20)
    var documento: String? = null,

    @Column(length = 160)
    var contato: String? = null,

    @Column(length = 40)
    var telefone: String? = null,

    @Column(length = 160)
    var email: String? = null,

    @Column(length = 300)
    var endereco: String? = null,

    @Column(length = 500)
    var observacao: String? = null,

    @Column(nullable = false)
    var ativo: Boolean = true,

    @Column(nullable = false)
    val criadoEm: LocalDateTime = LocalDateTime.now(),

    val origemResponsavelId: Long? = null,

    /** 1.19.0: bloqueado ou só com aprovação (V34). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var restricao: RestricaoCliente = RestricaoCliente.NENHUMA,

    @Column(length = 300)
    var motivoRestricao: String? = null,

    var restricaoEm: LocalDateTime? = null,

    var restricaoPor: String? = null
) {
    /** "Construtora X (CNPJ 12.345.678/0001-90)". */
    val descricao: String get() = nome + (documentoFormatado?.let { " ($it)" } ?: "")

    val documentoFormatado: String? get() = documento?.let { d ->
        when (d.length) {
            11 -> "CPF ${d.substring(0, 3)}.${d.substring(3, 6)}.${d.substring(6, 9)}-${d.substring(9)}"
            14 -> "CNPJ ${d.substring(0, 2)}.${d.substring(2, 5)}.${d.substring(5, 8)}/${d.substring(8, 12)}-${d.substring(12)}"
            else -> d
        }
    }
}

/** 1.19.0: restrição do cliente (V34). A devolução nunca é barrada. */
enum class RestricaoCliente(val rotulo: String, val descricao: String) {
    NENHUMA("Sem restrição", "Aluga normalmente."),
    APROVACAO("Só com aprovação", "O pedido só tem saída depois que um administrador ou gestor aprova."),
    BLOQUEADO("Bloqueado", "Não abre pedido novo e nada sai para ele; a devolução continua normal.")
}

enum class SituacaoPedido(val rotulo: String) {
    ABERTO("Aberto"),
    EM_SEPARACAO("Em separação"),
    ENTREGUE("Entregue"),
    DEVOLVIDO_PARCIAL("Devolvido em parte"),
    ENCERRADO("Encerrado"),
    CANCELADO("Cancelado");

    /** Com equipamento na rua, ou ainda saindo. */
    val emAndamento: Boolean get() = this in setOf(EM_SEPARACAO, ENTREGUE, DEVOLVIDO_PARCIAL)

    val finalizado: Boolean get() = this == ENCERRADO || this == CANCELADO
}

/** Pedido de locação (tabela pedidos_locacao, V29). O número do pedido é o id. */
@Entity
@Table(name = "pedidos_locacao")
class PedidoLocacaoEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var clienteId: Long,

    /** O nome do cliente no momento (o contrato continua legível se o cadastro mudar). */
    @Column(nullable = false)
    var cliente: String,

    var saidaPrevista: LocalDate? = null,

    @Column(nullable = false)
    var previsaoDevolucao: LocalDate,

    @Column(length = 300)
    var localEntrega: String? = null,

    @Column(length = 500)
    var observacao: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var situacao: SituacaoPedido = SituacaoPedido.ABERTO,

    @Column(nullable = false)
    var separacaoConcluida: Boolean = false,

    @Column(length = 500)
    var cancelamentoMotivo: String? = null,

    @Column(nullable = false)
    val registradoPor: String,

    @Column(nullable = false)
    val criadoEm: LocalDateTime = LocalDateTime.now(),

    /** 1.19.0: aprovação do pedido de cliente "só com aprovação" (V34). */
    var aprovadoPor: String? = null,

    var aprovadoEm: LocalDateTime? = null
)

/** O que o pedido pede (tabela pedido_linhas, V29): N de um tipo/marca/modelo, ou um bem específico. */
@Entity
@Table(name = "pedido_linhas")
class PedidoLinhaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val pedidoId: Long,

    @Column(nullable = false)
    val especifico: Boolean = false,

    val tagId: Long? = null,

    @Column(name = "tipo_equipamento_id")
    val tipoEquipamentoId: Long? = null,

    val marcaId: Long? = null,
    val modeloId: Long? = null,

    /** "Coletor Zebra MC3300" ou "000123 · Coletor Zebra MC3300". */
    @Column(nullable = false)
    val descricao: String,

    @Column(nullable = false)
    val quantidade: Int = 1
) {
    /** O bem combina com esta linha (sem olhar vaga nem situação). */
    fun combina(tag: TagEntity): Boolean =
        if (especifico) tagId == tag.id
        else tipoEquipamentoId == tag.tipoEquipamentoId &&
            (marcaId == null || marcaId == tag.marcaId) && (modeloId == null || modeloId == tag.modeloId)
}

enum class EstadoDevolucao(val rotulo: String) {
    OK("Sem avarias"),
    AVARIA("Com avaria"),
    FALTANDO_ACESSORIO("Faltando acessório")
}

/** Um equipamento que saiu no pedido (tabela pedido_itens, V29), com a devolução e a conferência dela. */
@Entity
@Table(name = "pedido_itens")
class PedidoItemEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val pedidoId: Long,

    @Column(nullable = false)
    val linhaId: Long,

    val tagId: Long? = null,

    @Column(nullable = false)
    val tagReal: String,

    val patrimonio: String? = null,
    val descricao: String? = null,

    @Column(nullable = false)
    val saidaEm: LocalDateTime,

    @Column(nullable = false)
    val saidaPor: String,

    var devolvidoEm: LocalDateTime? = null,
    var devolvidoPor: String? = null,
    var conferidoEm: LocalDateTime? = null,
    var conferidoPor: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var estadoDevolucao: EstadoDevolucao? = null,

    @Column(length = 500)
    var observacaoDevolucao: String? = null,

    var manutencaoId: Long? = null
) {
    val devolvido: Boolean get() = devolvidoEm != null
    val conferido: Boolean get() = conferidoEm != null
    val aguardandoConferencia: Boolean get() = devolvido && !conferido
}

/** Venda de um equipamento (tabela vendas, V29). */
@Entity
@Table(name = "vendas")
class VendaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val tagId: Long? = null,

    @Column(nullable = false)
    val tagReal: String,

    val patrimonio: String? = null,
    val descricao: String? = null,
    val clienteId: Long? = null,
    val cliente: String? = null,

    @Column(precision = 14, scale = 2)
    val valor: BigDecimal? = null,

    @Column(nullable = false)
    val data: LocalDate,

    @Column(length = 500)
    val observacao: String? = null,

    @Column(nullable = false)
    val registradaPor: String,

    @Column(nullable = false)
    val criadaEm: LocalDateTime = LocalDateTime.now(),

    var desfeitaEm: LocalDateTime? = null,
    var desfeitaPor: String? = null
)

/** Uma prorrogação da devolução prevista do pedido (tabela pedido_prorrogacoes, V32). */
@Entity
@Table(name = "pedido_prorrogacoes")
class PedidoProrrogacaoEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val pedidoId: Long,

    @Column(nullable = false)
    val dataAnterior: LocalDate,

    @Column(nullable = false)
    val dataNova: LocalDate,

    @Column(nullable = false, length = 500)
    val motivo: String,

    @Column(nullable = false)
    val registradaPor: String,

    @Column(nullable = false)
    val criadaEm: LocalDateTime = LocalDateTime.now()
)
