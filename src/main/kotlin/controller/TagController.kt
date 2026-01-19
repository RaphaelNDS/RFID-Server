package org.example.controller

import org.example.model.TagEntity
import org.example.repository.TagRepository
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

    // /api/tags/{epc}
    @GetMapping("/{epc}")
    fun buscar(@PathVariable epc: String): ResponseEntity<TagResponse> {
        val tag = service.buscarPorTag(epc)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(tag)
    }

    @PostMapping("/cadastrar")
    fun cadastrar(@RequestBody req: CadastroRequest) {
        service.cadastrarTag(req)
    }
}