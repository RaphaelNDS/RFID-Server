package org.example.service

import org.example.response.ServerStatusResponse
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class StatusService {

    fun getStatus(): ServerStatusResponse {

        val hora = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))

        return ServerStatusResponse(
            status = "ONLINE",
            horaServidor = "Desde $hora",
            versao = "1.3.3",
            banco = "H2 (memória)"
        )
    }
}