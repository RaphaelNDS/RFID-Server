package org.example.response

data class ServerStatusResponse(
    val status: String,
    /** Quando o servidor foi iniciado, ex.: "Desde 13/10/2026 11:20" */
    val horaServidor: String,
    val versao: String,
    val banco: String,
    /** Há quanto tempo o servidor está no ar, ex.: "2d 3h 15min" */
    val tempoOnline: String = "",
    /** Último backup do certificado HTTPS, ex.: "13/10/2026 02:00 (há 9h)" (o banco tem backup próprio) */
    val ultimoBackup: String = "",
    /** Impressão digital SHA-256 do certificado HTTPS (o responsável confere no coletor), ou o aviso de HTTPS desligado */
    val certificadoHttps: String = "",
    /** Último backup do banco (contêiner rfid-backup), ex.: "04/11/2026 14:39 (há 2h)" */
    val backupBanco: String = "",
    /** Backup do banco atrasado ou última tentativa vazia: a tela de status mostra um alerta */
    val backupBancoComProblema: Boolean = false
)
