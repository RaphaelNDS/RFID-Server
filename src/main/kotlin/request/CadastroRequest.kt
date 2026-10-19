package org.example.request

data class CadastroRequest(
    var tag: String = "",
    var codigoInterno: String = "",
    var patrimonio: String = "",
    var numeroSerie: String = "",

    var tipoEquipamentoId : Long? = null,
    var marcaId: Long? = null,
    var modeloId: Long? = null
)



