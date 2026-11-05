package org.example.dto

/** Tipo de equipamento na API do catálogo: só id e nome (a entidade levava as marcas e os modelos, uma consulta por item). */
data class TipoDTO(
    val id: Long,
    val nome: String
)
