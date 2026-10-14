package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/** Local onde se faz inventário (ex.: Sala 101, Almoxarifado). Criado na primeira vez que um coletor usa o nome. */
@Entity
@Table(name = "locais")
class LocalEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true, length = 120)
    val nome: String,

    @Column(nullable = false)
    val criadoEm: LocalDateTime = LocalDateTime.now()
)
