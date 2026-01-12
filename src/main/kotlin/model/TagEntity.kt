package org.example.model

import jakarta.persistence.*

@Entity
@Table(name = "tags")
class TagEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var codigoInterno: String = "",


    val tagHash: String,
    val tagReal: String,

    val modelo: String,
    val patrimonio: String,
    val numeroSerie: String
)

