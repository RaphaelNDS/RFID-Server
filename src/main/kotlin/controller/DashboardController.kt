package org.example.controller

import org.example.model.SituacaoBem
import org.example.repository.TagRepository
import org.example.service.AuthService
import org.example.service.InventarioService
import org.example.service.MovimentoService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Tela Início (2.0.0): um resumo simples. Quantos equipamentos estão no estoque e fora dele (com Etiquetas · Ver), as
 * últimas movimentações (com Movimentações · Ver) e o último inventário (com Leituras · Ver).
 */
@Controller
class DashboardController(
    private val authService: AuthService,
    private val tags: TagRepository,
    private val movimentos: MovimentoService,
    private val inventarios: InventarioService
) {

    @GetMapping("/dashboard")
    fun dashboard(model: Model): String {

        model.addAttribute("isAdmin", authService.temRole("ADMIN"))
        if (authService.temPermissao("RFID", "READ")) {
            val porSituacao = tags.contarPorSituacao().associate { (it[0] as SituacaoBem) to (it[1] as Long) }
            model.addAttribute("noEstoque", porSituacao[SituacaoBem.DISPONIVEL] ?: 0L)
            model.addAttribute("foraDoEstoque", porSituacao[SituacaoBem.FORA] ?: 0L)
            model.addAttribute("extraviados", porSituacao[SituacaoBem.EXTRAVIADO] ?: 0L)
        }
        if (authService.temPermissao("MOVIMENTO", "READ")) model.addAttribute("ultimasMovimentacoes", movimentos.ultimos())
        if (authService.temPermissao("LEITURA", "READ")) model.addAttribute("ultimoInventario", inventarios.listar().content.firstOrNull())

        return "dashboard"
    }
}
