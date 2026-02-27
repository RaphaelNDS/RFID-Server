package org.example.controller

import org.example.request.CadastroRequest
import org.example.request.LeituraRequest
import org.example.service.RfidService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/api/rfid")
class RfidController(
    private val service: RfidService
) {

    @PreAuthorize("@authService.temPermissao('TAG','READ')")
    @PostMapping("/cadastrar")
    fun cadastrar(@RequestBody req: CadastroRequest) {
        service.cadastrarTag(req)
    }

    data class LeituraRequest(val tag: String)

    @PreAuthorize("@authService.temPermissao('TAG','READ')")
    @PostMapping("/ler")
    fun ler(@RequestBody req: LeituraRequest) =
        service.buscarPorTag(req.tag)

    @PostMapping("/naocadastrada")
    fun salvarNao(@RequestBody req: LeituraRequest) {
        service.salvarNaoCadastrada(req.tag)
    }
}



