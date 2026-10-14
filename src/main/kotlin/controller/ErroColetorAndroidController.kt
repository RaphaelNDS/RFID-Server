package org.example.controller

import org.example.service.ErroColetorService
import org.example.service.ErroRecebido
import org.example.service.ResultadoErros
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Erros do app enviados pelo coletor (travamentos e falhas relevantes). Exige só a chave do coletor: o erro
 * pode ter acontecido sem operador logado. Responde 202 mesmo quando descarta por excesso, para o app não
 * ficar reenviando (os descartados vão para o log do servidor).
 */
@RestController
@RequestMapping("/api/android")
class ErroColetorAndroidController(
    private val erros: ErroColetorService
) {

    data class ErrosRequest(val itens: List<ErroRecebido> = emptyList())

    @PostMapping("/erros")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun receber(@RequestBody req: ErrosRequest, auth: Authentication): ResultadoErros = erros.receber(auth.name, req.itens)
}
