package org.example.controller

import org.example.model.AreaAuditoria
import org.example.service.AuditoriaService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/** Tela de auditoria: quem fez o quê em usuários, senhas, catálogo, app, exportações e backup. Só ADMIN. */
@PreAuthorize("hasRole('ADMIN')")
@Controller
@RequestMapping("/admin/auditoria")
class AuditoriaWebController(
    private val auditoria: AuditoriaService
) {

    @GetMapping
    fun listar(
        @RequestParam(required = false) area: String?,
        @RequestParam(required = false) busca: String?,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {
        // Área desconhecida na URL vira "todas", sem erro
        val areaFiltro = AreaAuditoria.values().firstOrNull { it.name == area }
        val termo = busca?.trim().orEmpty()
        val resultado = auditoria.listar(areaFiltro, termo, pagina - 1)

        model.addAttribute("registros", resultado.content)
        model.addAttribute("areas", AreaAuditoria.values())
        model.addAttribute("areaSelecionada", areaFiltro?.name)
        model.addAttribute("busca", termo)
        model.adicionarPaginacao(resultado, "/admin/auditoria", mapOf("area" to areaFiltro?.name, "busca" to termo))
        return "auditoria"
    }
}
