package org.example.model

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonManagedReference

@Entity
@Table(name = "marca")
class MarcaEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val nome: String,

    @ManyToOne
    @JoinColumn(name = "tipo_id", nullable = false)
    val tipo: TipoEquipamentoEntity,

    @JsonManagedReference
    @OneToMany(mappedBy = "marca", cascade = [CascadeType.ALL])
    val modelos: List<ModeloEntity> = emptyList()
)


