package org.example.response

data class ServerStatusResponse(
    val status: String,
    /** Quando o servidor foi iniciado, ex.: "Desde 13/10/2026 11:20" */
    val horaServidor: String,
    val versao: String,
    val banco: String,
    /** Há quanto tempo o servidor está no ar, ex.: "2d 3h 15min" */
    val tempoOnline: String = "",
    /** Último backup do banco, ex.: "13/10/2026 02:00 (há 9h)" */
    val ultimoBackup: String = "",
    /** Impressão digital SHA-256 do certificado HTTPS (o responsável confere no coletor), ou o aviso de HTTPS desligado */
    val certificadoHttps: String = ""
)
