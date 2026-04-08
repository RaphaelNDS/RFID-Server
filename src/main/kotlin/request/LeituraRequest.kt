package org.example.request

import java.time.LocalDateTime

data class LeituraRequest(val tag: String)

data class TagResponse(
    val modelo: String,
    val patrimonio: String,
    val numeroSerie: String
)

data class TagNaoCadastradaView(
    val tag: String,
    val dataHora: LocalDateTime
)