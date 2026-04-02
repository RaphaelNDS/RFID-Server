package org.example.controller

import org.example.request.LeituraAndroidRequest
import org.example.service.LeituraAndroidService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/android")
class LeituraAndroidController(
    private val leituraService: LeituraAndroidService
) {

    data class LeituraRequest(
        val tag: String
    )

    @PostMapping("/leitura")
    fun receberLeitura(@RequestBody req: LeituraRequest) {
        leituraService.salvarLeitura(req.tag)
    }
}
