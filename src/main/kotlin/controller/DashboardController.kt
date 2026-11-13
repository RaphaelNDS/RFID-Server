package org.example.controller

import org.example.model.SituacaoBem
import org.example.repository.TagNaoCadastradaRepository
import org.example.repository.TagRepository
import org.example.service.AuthService
import org.example.service.ColetorService
import org.example.service.InventarioService
import org.example.service.SituacaoContato
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Tela Início (2.1.0): um resumo simples. Etiquetas cadastradas no acervo e extraviadas, etiquetas pendentes (com
 * Etiquetas · Ver), o último inventário (com Leituras · Ver) e os coletores em uso agora (só o ADMIN, que vê a tela
 * Coletores).
 */
@Controller
class DashboardController(
    private val authService: AuthService,
    private val tags: TagRepository,
    private val pendentes: TagNaoCadastradaRepository,
    private val inventarios: InventarioService,
    private val coletores: ColetorService
) {

    @GetMapping("/dashboard")
    fun dashboard(model: Model): String {

        val isAdmin = authService.temRole("ADMIN")
        model.addAttribute("isAdmin", isAdmin)
        if (authService.temPermissao("RFID", "READ")) {
            val porSituacao = tags.contarPorSituacao().associate { (it[0] as SituacaoBem) to (it[1] as Long) }
            model.addAttribute("etiquetas", SituacaoBem.NO_ACERVO.sumOf { porSituacao[it] ?: 0L })
            model.addAttribute("extraviados", porSituacao[SituacaoBem.EXTRAVIADO] ?: 0L)
            model.addAttribute("pendentes", pendentes.count())
        }
        if (authService.temPermissao("LEITURA", "READ")) model.addAttribute("ultimoInventario", inventarios.listar().content.firstOrNull())
        if (isAdmin) {
            val lista = coletores.listar()
            model.addAttribute("coletoresEmUso", lista.count { it.situacao == SituacaoContato.EM_USO })
            model.addAttribute("coletoresTotal", lista.count { it.situacao != SituacaoContato.CHAVE_REMOVIDA })
        }

        return "dashboard"
    }
}
