package org.example.controller

import org.example.service.LinhaDoTempoService
import org.example.service.RfidService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

/** Linha do tempo do equipamento (1.16.0): tudo o que aconteceu com ele numa tela só. Etiquetas · Ver. */
@Controller
class LinhaDoTempoWebController(private val service: LinhaDoTempoService, private val rfid: RfidService) {

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/admin/etiquetas/{id}/linha-do-tempo")
    fun linhaDoTempo(@PathVariable id: Long, model: Model): String {
        model.addAttribute("l", service.montar(rfid.buscarPorId(id)))
        model.addAttribute("fmt", org.example.util.FormatoBr)
        return "linha-do-tempo"
    }
}
