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

@Controller
@RequestMapping("/admin/leituras-android")
class LeituraAndroidAdminController(
    private val leituraRepo: LeituraAndroidRepository
) {

    @GetMapping
    fun listar(
        @RequestParam(required = false) dia: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) tipo: String?,
        @RequestParam(required = false) marca: String?,
        @RequestParam(required = false) modelo: String?,
        @RequestParam(required = false) numeroSerie: String?,
        model: Model
    ): String {

        val spec = Specification<LeituraAndroidEntity> { root, _, cb ->
            val predicates = mutableListOf<Predicate>()

            // 🔹 filtro por DIA
            dia?.takeIf { it.isNotBlank() }?.let {
                val data = LocalDate.parse(it)
                val inicio = data.atStartOfDay()
                val fim = data.atTime(23, 59, 59)

                predicates.add(
                    cb.between(root.get("dataHora"), inicio, fim)
                )
            }

            tag?.takeIf { it.isNotBlank() }?.let {
                predicates.add(cb.like(root.get("tag"), "%$it%"))
            }
            tipo?.takeIf { it.isNotBlank() }?.let {
                predicates.add(cb.like(root.get("tipo"), "%$it%"))
            }
            marca?.takeIf { it.isNotBlank() }?.let {
                predicates.add(cb.like(root.get("marca"), "%$it%"))
            }
            modelo?.takeIf { it.isNotBlank() }?.let {
                predicates.add(cb.like(root.get("modelo"), "%$it%"))
            }
            numeroSerie?.takeIf { it.isNotBlank() }?.let {
                predicates.add(cb.like(root.get("numeroSerie"), "%$it%"))
            }

            cb.and(*predicates.toTypedArray())
        }

        val leituras = leituraRepo.findAll(spec)

        val contadorPorDia = leituraRepo.contarPorDia()
            .associate { it[0].toString() to (it[1] as Long) }

        model.addAttribute("leituras", leituras)
        model.addAttribute("contadorPorDia", contadorPorDia)
        model.addAttribute("diaSelecionado", dia)

        return "leituras-android"
    }
}
