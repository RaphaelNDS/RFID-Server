package org.example.response

data class TagViewAdmin(
    val tag: String,
    val codigoInterno: String,
    val tipoNome: String?,
    val marcaNome: String?,
    val modeloNome: String?,
    val patrimonio: String,
    val numeroSerie: String
)
