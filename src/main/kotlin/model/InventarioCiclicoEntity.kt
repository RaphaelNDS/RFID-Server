package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/** Configuração do inventário cíclico (1.27.0, tabela inventario_ciclico, V40). Uma linha só (id = 1). */
@Entity
@Table(name = "inventario_ciclico")
class InventarioCiclicoEntity(
    @Id
    val id: Int = 1,

    @Column(nullable = false)
    var ativo: Boolean = false,

    /** Cada local precisa ser inventariado pelo menos uma vez a cada tantas semanas. */
    @Column(nullable = false)
    var semanas: Int = 4,

    var alteradoPor: String? = null,

    var alteradoEm: LocalDateTime? = null
)
