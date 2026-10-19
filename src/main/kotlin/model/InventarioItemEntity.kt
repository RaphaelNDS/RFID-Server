package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Etiqueta lida num inventário. Os dados do cadastro são copiados no recebimento, então o
 * relatório do inventário não muda se a etiqueta for editada ou excluída depois.
 */
@Entity
@Table(
    name = "inventario_itens",
    uniqueConstraints = [UniqueConstraint(name = "uk_inventario_itens_tag", columnNames = ["inventario_id", "tag"])]
)
class InventarioItemEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "inventario_id", nullable = false, length = 36)
    val inventarioId: String,

    @Column(nullable = false)
    val tag: String,

    @Column(nullable = false)
    val lidoEm: LocalDateTime,

    /** A etiqueta estava cadastrada no momento em que o servidor recebeu a leitura. */
    @Column(nullable = false)
    val cadastrada: Boolean,

    val codigoInterno: String? = null,
    val patrimonio: String? = null,
    val tipo: String? = null,
    val marca: String? = null,
    val modelo: String? = null,

    /** Nome do operador que leu a etiqueta (nulo nos anteriores ao login de operador). */
    val operador: String? = null
)
