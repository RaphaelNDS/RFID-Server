package org.example.model

import com.fasterxml.jackson.annotation.JsonManagedReference
import jakarta.persistence.*

@Entity
@Table(name = "tipo_equipamento")
class TipoEquipamentoEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false, unique = true)
    var nome: String,

    @JsonManagedReference   // ✅
    @OneToMany(mappedBy = "tipo", cascade = [CascadeType.ALL])
    var marcas: List<MarcaEntity> = emptyList()
)