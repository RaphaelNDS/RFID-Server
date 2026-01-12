package org.example.model

import jakarta.persistence.*


@Entity
@Table(name = "tags")
data class TagEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val tagHash: String,

    @Column(nullable = false)
    val tagReal: String,

    val modelo: String,
    val patrimonio: String,
    val numeroSerie: String
)
