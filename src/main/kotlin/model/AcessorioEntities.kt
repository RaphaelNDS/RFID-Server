package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/** Acessório que acompanha os equipamentos na locação (tabela acessorios, V33): carregador, bateria, cabo... Contado, sem etiqueta. */
@Entity
@Table(name = "acessorios")
class AcessorioEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 120)
    var nome: String,

    @Column(nullable = false)
    var ativo: Boolean = true,

    @Column(nullable = false)
    val criadoEm: LocalDateTime = LocalDateTime.now()
)

/** Um acessório do kit de um tipo de equipamento, ou de um modelo (tabela kit_itens, V33). [modeloId] nulo: kit do tipo. */
@Entity
@Table(name = "kit_itens")
class KitItemEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "tipo_equipamento_id", nullable = false)
    val tipoEquipamentoId: Long,

    val modeloId: Long? = null,

    @Column(nullable = false)
    val acessorioId: Long,

    @Column(nullable = false)
    var quantidade: Int = 1
)

/**
 * Um acessório do pedido de locação (tabela pedido_acessorios, V33): quanto foi pedido, enviado e devolvido. [linhaId]:
 * a linha que o trouxe pelo kit (nulo: avulso).
 */
@Entity
@Table(name = "pedido_acessorios")
class PedidoAcessorioEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val pedidoId: Long,

    var linhaId: Long? = null,

    val acessorioId: Long? = null,

    @Column(nullable = false, length = 160)
    val descricao: String,

    @Column(nullable = false)
    var quantidade: Int,

    @Column(nullable = false)
    var enviada: Int = 0,

    @Column(nullable = false)
    var devolvida: Int = 0,

    var atualizadoEm: LocalDateTime? = null,
    var atualizadoPor: String? = null
) {
    /** Enviados que ainda não voltaram. */
    val faltando: Int get() = (enviada - devolvida).coerceAtLeast(0)

    /** Pedidos que ainda não foram enviados. */
    val aEnviar: Int get() = (quantidade - enviada).coerceAtLeast(0)
}
