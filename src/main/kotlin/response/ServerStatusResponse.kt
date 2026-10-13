package org.example.response

data class ServerStatusResponse(
    val status: String,
    /** Quando o servidor foi iniciado, ex.: "Desde 13/10/2026 11:20" */
    val horaServidor: String,
    val versao: String,
    val banco: String,
    /** Há quanto tempo o servidor está no ar, ex.: "2d 3h 15min" */
    val tempoOnline: String = ""
)
