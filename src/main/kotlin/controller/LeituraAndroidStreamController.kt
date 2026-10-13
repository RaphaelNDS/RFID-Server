package org.example.controller

import org.example.model.LeituraAndroidEntity
import org.example.service.LeituraEventosService
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

@RestController
@RequestMapping("/admin/stream")
class LeituraAndroidStreamController(
    private val eventos: LeituraEventosService
) {

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/leituras", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): Flux<ServerSentEvent<LeituraAndroidEntity>> =
        eventos.comoSse("leituras", eventos.leituras())
}
