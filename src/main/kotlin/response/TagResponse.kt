package org.example.response

data class TagResponse(
val codigoInterno: String,
val tipo: String?,
val marca: String?,
val modelo: String?,
val patrimonio: String,
val numeroSerie: String,

// Ficha do bem no coletor (1.5.0). Opcionais: null quando não há (o app mostra o que vier)
/** Local do último inventário finalizado em que a etiqueta foi encontrada. */
val ultimoLocal: String? = null,
val ultimoInventarioEm: java.time.OffsetDateTime? = null,
/** Última leitura da etiqueta (avulsa ou em lote) e o operador dela. */
val ultimaLeituraEm: java.time.OffsetDateTime? = null,
val ultimaLeituraPor: String? = null
)

