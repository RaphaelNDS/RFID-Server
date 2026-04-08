package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "leituras_android")
data class LeituraAndroidEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val tag: String,

    val codigoInterno: String? = null,
    val tipo: String? = null,
    val marca: String? = null,
    val modelo: String? = null,
    val patrimonio: String? = null,
    val numeroSerie: String? = null,

    @Enumerated(EnumType.STRING)
    var movimento: TipoMovimento? = null,

    var motivoEntrada: String? = null,
    var destino: String? = null,

    val dataHora: LocalDateTime = LocalDateTime.now()
)

enum class TipoMovimento {
    ENTRADA,
    SAIDA
}