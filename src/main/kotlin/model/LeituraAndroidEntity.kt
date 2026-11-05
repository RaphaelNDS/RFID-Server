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

    val dataHora: LocalDateTime = LocalDateTime.now(),

    /**
     * A hora que o coletor informou (no fuso do servidor), mesmo que esteja no futuro por relógio adiantado: identifica
     * o reenvio da mesma leitura (índice único com a tag, V16). Nula quando o coletor não informou a hora.
     */
    val lidoEmColetor: LocalDateTime? = null,

    /** Nome do operador logado no coletor. Nulo nas leituras anteriores ao login de operador. */
    val operador: String? = null
)
