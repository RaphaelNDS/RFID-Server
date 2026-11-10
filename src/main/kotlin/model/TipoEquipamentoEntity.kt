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

    /** 1.8.0 (V25): vida útil em meses para a depreciação linear dos bens deste tipo. Null = não deprecia. */
    @Column(name = "vida_util_meses")
    var vidaUtilMeses: Int? = null,

    /** 1.8.0 (V25): valor residual, em % do valor de aquisição (o que sobra no fim da vida útil). Null = 0%. */
    @Column(name = "valor_residual_pct", precision = 5, scale = 2)
    var valorResidualPct: java.math.BigDecimal? = null,

    @JsonManagedReference   // ✅
    @OneToMany(mappedBy = "tipo", cascade = [CascadeType.ALL])
    var marcas: List<MarcaEntity> = emptyList()
)
