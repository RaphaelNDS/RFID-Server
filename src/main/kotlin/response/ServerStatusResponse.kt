package org.example.response

data class ServerStatusResponse(
    val status: String,
    val horaServidor: String,
    val versao: String,
    val banco: String
)