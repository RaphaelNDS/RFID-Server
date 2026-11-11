package org.example.model

import com.fasterxml.jackson.annotation.JsonManagedReference
import jakarta.persistence.*

/**
 * Tipo de equipamento do catálogo (tabela tipo_equipamento). As colunas vida_util_meses e valor_residual_pct (V25, da
 * depreciação da 1.8.0) continuam no banco, sem uso desde a 1.12.0; saíram da entidade na 1.18.1.
 */
@Entity
@Table(name = "tipo_equipamento")
class TipoEquipamentoEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(nullable = false, unique = true)
    var nome: String,

    @JsonManagedReference
    @OneToMany(mappedBy = "tipo", cascade = [CascadeType.ALL])
    var marcas: List<MarcaEntity> = emptyList()
)
