package org.example.controller

import org.example.model.LeituraAndroidEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

@RestController
@RequestMapping("/admin/stream")
class LeituraAndroidStreamController {

    private val sink =
        Sinks.many().multicast().onBackpressureBuffer<LeituraAndroidEntity>()

    fun publicar(leitura: LeituraAndroidEntity) {
        sink.tryEmitNext(leitura)
    }

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/leituras")
    fun stream(): Flux<LeituraAndroidEntity> =
        sink.asFlux()
}