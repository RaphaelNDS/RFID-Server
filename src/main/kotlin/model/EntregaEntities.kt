package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/** Comprovante de entrega de um pedido de locação (1.23.0, tabela entregas_pedido, V37). */
@Entity
@Table(name = "entregas_pedido")
class EntregaPedidoEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val pedidoId: Long,

    @Column(nullable = false, length = 160)
    val recebidoPor: String,

    @Column(length = 40)
    val documento: String? = null,

    @Column(length = 500)
    val observacao: String? = null,

    /** Os equipamentos com o cliente na hora da entrega, um por linha. */
    @Column(nullable = false, length = 8000)
    val equipamentos: String,

    /** A assinatura desenhada na tela, em PNG. */
    @Column(nullable = false, columnDefinition = "bytea")
    val assinatura: ByteArray,

    @Column(nullable = false)
    val registradaPor: String,

    @Column(nullable = false)
    val registradaEm: LocalDateTime = LocalDateTime.now()
) {
    val listaEquipamentos: List<String> get() = equipamentos.lines().filter { it.isNotBlank() }
}

/** Foto da entrega (1.23.0, tabela entrega_fotos, V37). */
@Entity
@Table(name = "entrega_fotos")
class EntregaFotoEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val entregaId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val tipo: TipoAnexo,

    @Column(nullable = false)
    val tamanho: Int,

    @Column(nullable = false, columnDefinition = "bytea")
    val conteudo: ByteArray
)
