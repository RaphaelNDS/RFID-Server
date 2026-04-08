package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "tags_nao_cadastradas")
data class TagNaoCadastradaEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val tag: String,

    @Column(nullable = false)
    val dataHora: LocalDateTime
)