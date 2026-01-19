package org.example.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonBackReference


@Entity
@Table(name = "modelo")
class ModeloEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val nome: String,

    @JsonBackReference
    @ManyToOne
    @JoinColumn(name = "marca_id", nullable = false)
    val marca: MarcaEntity
)

