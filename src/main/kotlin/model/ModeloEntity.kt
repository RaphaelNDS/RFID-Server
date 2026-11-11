package org.example.model

import com.fasterxml.jackson.annotation.JsonBackReference
import jakarta.persistence.*

@Entity
@Table(name = "modelo")
class ModeloEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var nome: String,

    @JsonBackReference
    @ManyToOne
    @JoinColumn(name = "marca_id", nullable = false)
    var marca: MarcaEntity
)
