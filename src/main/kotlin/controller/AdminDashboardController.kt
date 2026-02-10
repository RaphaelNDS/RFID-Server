package org.example.controller

import jakarta.persistence.criteria.Predicate
import org.example.model.LeituraAndroidEntity
import org.example.repository.LeituraAndroidRepository
import org.example.service.LeituraAndroidConsultaService
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.LocalDateTime

@Controller
@RequestMapping("/admin")
class AdminDashboardController(
    private val leituraRepo: LeituraAndroidRepository
) {

    @GetMapping("/dashboard")
    fun dashboard(
        @RequestParam(required = false) inicio: String?,
        @RequestParam(required = false) fim: String?,
        model: Model
    ): String {

        val agora = LocalDateTime.now()

        val dataInicio = inicio?.let { LocalDate.parse(it).atStartOfDay() }
            ?: agora.minusDays(7)

        val dataFim = fim?.let { LocalDate.parse(it).atTime(23,59,59) }
            ?: agora

        val total = leituraRepo.contarTotal()
        val hoje = leituraRepo.contarDesde(LocalDate.now().atStartOfDay())
        val ultimaHora = leituraRepo.contarDesde(agora.minusHours(1))

        val ultimos7Dias = leituraRepo
            .contarUltimosDias(agora.minusDays(7))
            .associate { it[0].toString() to (it[1] as Long) }

        val hojeCount = leituraRepo.contarDesde(LocalDate.now().atStartOfDay())
        val ontemInicio = LocalDate.now().minusDays(1).atStartOfDay()
        val ontemFim = LocalDate.now().minusDays(1).atTime(23,59,59)

        val ontem = leituraRepo.buscarPorPeriodo(ontemInicio, ontemFim).size

        val crescimento = if (ontem > 0)
            ((hojeCount - ontem) * 100.0 / ontem)
        else 0.0

        val porTipo = leituraRepo.contarPorTipo()
            .associate { it[0].toString() to (it[1] as Long) }

        val porMarca = leituraRepo.contarPorMarca()
            .associate { it[0].toString() to (it[1] as Long) }

        model.addAttribute("totalLeituras", total)
        model.addAttribute("leiturasHoje", hoje)
        model.addAttribute("leiturasUltimaHora", ultimaHora)
        model.addAttribute("crescimento", "%.2f".format(crescimento))
        model.addAttribute("dados7dias", ultimos7Dias)
        model.addAttribute("dadosTipo", porTipo)
        model.addAttribute("dadosMarca", porMarca)

        return "admin-dashboard"
    }
}
