package org.example.model

import jakarta.persistence.*

@Entity
@Table(name = "tipo_equipamento")
class TipoEquipamentoEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val nome: String,

    @OneToMany(mappedBy = "tipo", cascade = [CascadeType.ALL])
    val marcas: List<MarcaEntity> = emptyList()
)


