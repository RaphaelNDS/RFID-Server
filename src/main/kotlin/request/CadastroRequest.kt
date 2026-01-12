package org.example.request

data class CadastroRequest(
    val codigoInterno: String,
    val tag: String,
    val modelo: String,
    val patrimonio: String,
    val numeroSerie: String
)

