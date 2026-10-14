package org.example.controller

import org.example.service.DashboardLeiturasService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

@Controller
@RequestMapping("/admin")
class AdminDashboardController(
    private val dashboardService: DashboardLeiturasService
) {

    @PreAuthorize("@authService.temPermissao('DASHBOARD','READ')")
    @GetMapping("/dashboard")
    fun dashboard(
        @RequestParam(required = false) inicio: String?,
        @RequestParam(required = false) fim: String?,
        model: Model
    ): String {

        val periodo = dashboardService.resolverPeriodo(inicio, fim)
        val dados = dashboardService.calcular(periodo)

        model.addAttribute("inicio", periodo.inicio)
        model.addAttribute("fim", periodo.fim)
        model.addAttribute("aviso", periodo.aviso)

        model.addAttribute("totalPeriodo", dados.totalPeriodo)
        model.addAttribute("leiturasHoje", dados.leiturasHoje)
        model.addAttribute("leiturasUltimaHora", dados.leiturasUltimaHora)
        model.addAttribute("crescimento", dados.crescimento)
        model.addAttribute("dadosPorDia", dados.porDia)
        model.addAttribute("dadosTipo", dados.porTipo)
        model.addAttribute("dadosMarca", dados.porMarca)

        return "admin-dashboard"
    }
}
