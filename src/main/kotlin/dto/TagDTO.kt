package org.example.dto

@JvmRecord
data class TagDTO(
val tag: String,
val codigoInterno: String?,
val tipoNome: String?,
val marcaNome: String?,
val modeloNome: String?,
val patrimonio: String?,
val numeroSerie: String?
)