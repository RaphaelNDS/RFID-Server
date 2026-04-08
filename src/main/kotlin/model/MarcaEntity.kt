package org.example.model

import com.fasterxml.jackson.annotation.JsonBackReference
import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonManagedReference

@Entity
@Table(name = "marca")
class MarcaEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false)
    var nome: String,

    @ManyToOne
    @JoinColumn(name = "tipo_id", nullable = false)
    @JsonBackReference   // ✅
    var tipo: TipoEquipamentoEntity,

    @JsonManagedReference
    @OneToMany(mappedBy = "marca", cascade = [CascadeType.ALL])
    var modelos: List<ModeloEntity> = emptyList()
)