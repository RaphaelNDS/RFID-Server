package org.example.request

data class TipoEquipamentoRequest(
    var nome: String = "",
    var marcaId: Long? = null,
    var modeloId: Long? = null
)