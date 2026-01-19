package org.example.controller

import org.example.request.CadastroRequest
import org.example.request.LeituraRequest
import org.example.service.RfidService
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/api/rfid")
class RfidController(
    private val service: RfidService
) {

    @PostMapping("/cadastrar")
    fun cadastrar(@RequestBody req: CadastroRequest) {
        service.cadastrarTag(req)
    }

    data class LeituraRequest(val tag: String)

    @PostMapping("/ler")
    fun ler(@RequestBody req: LeituraRequest) =
        service.buscarPorTag(req.tag)

    @PostMapping("/naocadastrada")
    fun salvarNao(@RequestBody req: LeituraRequest) {
        service.salvarNaoCadastrada(req.tag)
    }
}



