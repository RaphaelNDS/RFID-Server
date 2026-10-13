package org.example.controller

import org.example.service.LeituraEventosService
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

@PreAuthorize("@authService.temPermissao('DASHBOARD','READ')")
@RestController
@RequestMapping("/admin/sse")
class DashboardSSEController(
    private val eventos: LeituraEventosService
) {

    // Envia "update" quando chegam leituras novas; a tela admin-dashboard recarrega ao receber
    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): Flux<ServerSentEvent<String>> =
        eventos.comoSse("dashboard", eventos.avisosDashboard())
}
