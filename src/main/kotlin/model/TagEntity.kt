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

    @Column(nullable = false, unique = true)
    val tagHash: String,

    @Column(nullable = false, unique = true)
    val tagReal: String,

    @Column(name = "tipo_equipamento_id")
    var tipoEquipamentoId: Long? = null,

    @Column(name = "marca_id")
    var marcaId: Long? = null,

    @Column(name = "modelo_id")
    var modeloId: Long? = null,

    var patrimonio: String = "",
    var numeroSerie: String = ""
)

