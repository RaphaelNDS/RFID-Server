package org.example.controller

import org.example.service.ColetorService
import org.example.service.SituacaoContato
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

/** Tela Coletores: último contato, IP, versão do app e operador de cada coletor. Só ADMIN, como Usuários. */
@Controller
@RequestMapping("/admin/coletores")
class ColetorWebController(
    private val coletores: ColetorService
) {

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    fun listar(model: Model): String {
        val lista = coletores.listar()

        model.addAttribute("coletores", lista)
        model.addAttribute("emUso", lista.count { it.situacao == SituacaoContato.EM_USO })
        model.addAttribute("desatualizados", lista.count { it.desatualizado })
        model.addAttribute("semContato", lista.count { it.situacao == SituacaoContato.SEM_CONTATO || it.situacao == SituacaoContato.NUNCA })
        model.addAttribute("versaoMaisNova", lista.filter { it.codigoVersao != null }.maxByOrNull { it.codigoVersao!! }
            ?.let { ColetorService.descreverVersao(it.versaoApp, it.codigoVersao) })
        return "coletores"
    }
}
