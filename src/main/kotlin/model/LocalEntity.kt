package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Local onde ficam os bens e se faz inventário (ex.: Sala 101, Almoxarifado). Criado na tela Locais ou na primeira vez
 * que um coletor usa o nome num inventário. Desde a 1.6.0 pertence a uma unidade (opcional: os de antes e os criados
 * pelo coletor ficam sem) e pode ser desativado (some das escolhas, mas continua nos bens e inventários antigos).
 * O nome continua único no sistema todo: o coletor identifica o local pelo nome.
 */
@Entity
@Table(name = "locais")
class LocalEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true, length = 120)
    var nome: String,

    @Column(nullable = false)
    val criadoEm: LocalDateTime = LocalDateTime.now(),

    @Column(name = "unidade_id")
    var unidadeId: Long? = null,

    @Column(nullable = false)
    var ativo: Boolean = true
)
