package org.example.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

@RestController
@RequestMapping("/admin/sse")
class DashboardSSEController {

    private val sink =
        Sinks.many().multicast().onBackpressureBuffer<String>()

    fun atualizar() {
        sink.tryEmitNext("update")
    }

    @GetMapping(produces = ["text/event-stream"])
    fun stream(): Flux<String> = sink.asFlux()
}
