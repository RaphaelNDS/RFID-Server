package org.example.request

import org.example.model.TipoMovimento

data class MovimentoExpedicaoRequest(
    val movimento: TipoMovimento,
    val motivoEntrada: String? = null,
    val destino: String? = null
)