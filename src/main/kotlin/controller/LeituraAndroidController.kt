package org.example.controller

import org.example.security.nomeOperador
import org.example.service.LeituraAndroidService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/android")
class LeituraAndroidController(
    private val leituraService: LeituraAndroidService
) {

    data class LeituraRequest(
        val tag: String,
        // Quando o coletor leu a tag (ISO-8601 com fuso, ex.: 2026-10-13T13:03:51-03:00).
        // Leituras feitas offline chegam depois: sem este campo, valeria a hora do envio.
        val lidoEm: OffsetDateTime? = null
    )

    @PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @PostMapping("/leitura")
    fun receberLeitura(@RequestBody req: LeituraRequest, auth: Authentication) {
        leituraService.salvarLeitura(req.tag, req.lidoEm, auth.nomeOperador())
    }
}
