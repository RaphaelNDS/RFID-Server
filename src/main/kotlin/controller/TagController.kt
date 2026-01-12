package org.example.controller

import org.example.request.TagNaoCadastradaView
import org.example.request.CadastroRequest
import org.example.response.TagResponse
import org.example.service.RfidService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/tags")
class TagController(
    private val service: RfidService
) {

    @GetMapping("/{epc}")
    fun buscar(@PathVariable epc: String): ResponseEntity<TagResponse> {
        val tag = service.buscarPorTag(epc)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(tag)
    }

    @PostMapping("/naocadastrada")
    fun salvarNao(@RequestBody req: RfidController.LeituraRequest) {
        service.salvarNaoCadastrada(req.tag)
    }

    @GetMapping("/naocadastradas")
    fun listarNao(): List<TagNaoCadastradaView> =
        service.listarNaoCadastradas()

    @PostMapping("/cadastrar")
    fun cadastrar(@RequestBody req: CadastroRequest) {
        service.cadastrarTag(req)
    }
}


