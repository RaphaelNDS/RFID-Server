package org.example.controller

import org.example.repository.TagRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController


@RestController
class TagLocale(
    private val tagRepository: TagRepository
) {

    @GetMapping("/api/tags/patrimonio/{patrimonio}")
    fun buscarPorPatrimonio(
        @PathVariable patrimonio: String
    ): ResponseEntity<Map<String, String>> {

        val tagOpt = tagRepository.findByPatrimonio(patrimonio)

        return if (tagOpt.isPresent) {
            val tag = tagOpt.get()

            val resp = mapOf(
                "epc" to tag.tagReal
            )

            ResponseEntity.ok(resp)

        } else {
            ResponseEntity.notFound().build()
        }
    }
}