package org.example.controller

import org.example.request.MarcaRequest
import org.example.service.CatalogoService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@PreAuthorize("hasAnyRole('ADMIN','GESTOR')")
@Controller
@RequestMapping("/admin/catalogo")
class CatalogoViewController(
    private val catalogoService: CatalogoService
) {
    @PreAuthorize("@authService.temPermissao('CATALOGO','READ')")
    @GetMapping
    fun catalogo(model: Model): String {
        carregar(model)
        return "catalogo"
    }
    @PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
    @GetMapping("/tipos")
    fun tipos(model: Model): String {
        model.addAttribute("tipos", catalogoService.listarTipos())
        return "tipos"
    }

    @GetMapping("/marcas")
    fun marcas(model: Model): String {
        model.addAttribute("marcas", catalogoService.listarMarcas())
        return "marcas"
    }

    @GetMapping("/modelos")
    fun modelos(model: Model): String {
        model.addAttribute("modelos", catalogoService.listarModelos())
        return "modelos"
    }
//    @PostMapping("/tipo")
//    fun salvarTipo(req: TipoEquipamentoRequest): String {
//        catalogoService.salvarTipo(req)
//        return "redirect:/admin/catalogo"
//    }
//
//    @PostMapping("/marca")
//    fun salvarMarca(req: MarcaRequest): String {
//        catalogoService.salvarMarca(req)
//        return "redirect:/admin/catalogo"
//    }
//
//    @PostMapping("/modelo")
//    fun salvarModelo(req: ModeloRequest): String {
//        catalogoService.salvarModelo(req)
//        return "redirect:/admin/catalogo"
//    }


    @PostMapping("/tipo")
    fun salvarTipo(req: TipoEquipamentoRequest, model: Model): String {
        return try {
            catalogoService.salvarTipo(req)
            "redirect:/admin/catalogo"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            carregar(model)
            "catalogo"
        }
    }

    @PostMapping("/marca")
    fun salvarMarca(req: MarcaRequest, model: Model): String {
        return try {
            catalogoService.salvarMarca(req)
            "redirect:/admin/catalogo"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            carregar(model)
            "catalogo"
        }
    }

    @PostMapping("/modelo")
    fun salvarModelo(req: ModeloRequest, model: Model): String {
        return try {
            catalogoService.salvarModelo(req)
            "redirect:/admin/catalogo"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            carregar(model)
            "catalogo"
        }
    }

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter {

        val emitter = SseEmitter(Long.MAX_VALUE)

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate({
            try {
                emitter.send(SseEmitter.event().data("update"))
            } catch (e: Exception) {
                emitter.complete()
            }
        }, 0, 10, TimeUnit.SECONDS)

        return emitter
    }


    private fun carregar(model: Model) {
        model.addAttribute("tipos", catalogoService.listarTipos())
        model.addAttribute("marcas", catalogoService.listarMarcas())
        model.addAttribute("modelos", catalogoService.listarModelos())
    }
}