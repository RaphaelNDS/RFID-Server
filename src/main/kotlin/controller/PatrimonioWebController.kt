package org.example.controller

import org.example.service.PatrimonioCadastrosService
import org.example.service.RfidService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Clock
import java.time.LocalDate

/**
 * Cadastros da gestão de patrimônio (1.6.0): unidades e locais, responsáveis e o termo de responsabilidade. Mesma
 * permissão do Catálogo (cadastros de apoio das etiquetas): Ver para abrir, Criar/Editar para mudar.
 */
@Controller
@RequestMapping("/admin")
class PatrimonioWebController(
    private val cadastros: PatrimonioCadastrosService,
    private val rfid: RfidService,
    private val relogio: Clock
) {

    // ================= LOCAIS =================

    @PreAuthorize("@authService.temPermissao('CATALOGO','READ')")
    @GetMapping("/locais")
    fun locais(model: Model): String {
        model.addAttribute("grupos", cadastros.locaisPorUnidade())
        model.addAttribute("unidades", cadastros.listarUnidades())
        return "locais"
    }

    @PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
    @PostMapping("/locais/unidades")
    fun novaUnidade(@RequestParam nome: String, redirect: RedirectAttributes): String =
        executar(redirect, "/admin/locais") { "Unidade ${cadastros.salvarUnidade(null, nome).nome} cadastrada." }

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PostMapping("/locais/unidades/{id}")
    fun editarUnidade(@PathVariable id: Long, @RequestParam nome: String, @RequestParam(defaultValue = "false") ativa: Boolean,
                      redirect: RedirectAttributes): String =
        executar(redirect, "/admin/locais") { "Unidade ${cadastros.salvarUnidade(id, nome, ativa).nome} alterada." }

    @PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
    @PostMapping("/locais")
    fun novoLocal(@RequestParam nome: String, @RequestParam(required = false) unidade: Long?, redirect: RedirectAttributes): String =
        executar(redirect, "/admin/locais") { "Local ${cadastros.salvarLocal(null, nome, unidade?.takeIf { it > 0 }).nome} cadastrado." }

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PostMapping("/locais/{id}")
    fun editarLocal(@PathVariable id: Long, @RequestParam nome: String, @RequestParam(required = false) unidade: Long?,
                    @RequestParam(defaultValue = "false") ativo: Boolean, redirect: RedirectAttributes): String =
        executar(redirect, "/admin/locais") { "Local ${cadastros.salvarLocal(id, nome, unidade?.takeIf { it > 0 }, ativo).nome} alterado." }

    // ================= RESPONSÁVEIS =================

    @PreAuthorize("@authService.temPermissao('CATALOGO','READ')")
    @GetMapping("/responsaveis")
    fun responsaveis(model: Model): String {
        model.addAttribute("responsaveis", cadastros.listarResponsaveis())
        model.addAttribute("bens", cadastros.bensPorResponsavel())
        return "responsaveis"
    }

    @PreAuthorize("@authService.temPermissao('CATALOGO','CREATE')")
    @PostMapping("/responsaveis")
    fun novoResponsavel(@RequestParam nome: String, @RequestParam(required = false) setor: String?, @RequestParam(required = false) matricula: String?,
                        @RequestParam(required = false) email: String?, redirect: RedirectAttributes): String =
        executar(redirect, "/admin/responsaveis") { "Responsável ${cadastros.salvarResponsavel(null, nome, setor, matricula, email).nome} cadastrado." }

    @PreAuthorize("@authService.temPermissao('CATALOGO','UPDATE')")
    @PostMapping("/responsaveis/{id}")
    fun editarResponsavel(@PathVariable id: Long, @RequestParam nome: String, @RequestParam(required = false) setor: String?,
                          @RequestParam(required = false) matricula: String?, @RequestParam(required = false) email: String?,
                          @RequestParam(defaultValue = "false") ativo: Boolean, redirect: RedirectAttributes): String =
        executar(redirect, "/admin/responsaveis") { "Responsável ${cadastros.salvarResponsavel(id, nome, setor, matricula, email, ativo).nome} alterado." }

    /**
     * Termo de responsabilidade: página pronta para imprimir (Ctrl+P → Salvar como PDF) com os bens do responsável que
     * não foram baixados, a declaração e os campos de assinatura.
     */
    @PreAuthorize("@authService.temPermissao('CATALOGO','READ') and @authService.temPermissao('RFID','READ')")
    @GetMapping("/responsaveis/{id}/termo")
    fun termo(@PathVariable id: Long, model: Model): String {
        val responsavel = cadastros.responsavel(id)
        // Sem situação escolhida, o filtro já deixa os baixados de fora; pelo patrimônio (sem patrimônio por último)
        val bens = rfid.listarTodasParaAdmin(null, org.example.service.FiltroEtiquetas(responsavelId = id))
            .sortedWith(compareBy<org.example.response.TagViewAdmin> { it.patrimonio.ifBlank { "￿" } }.thenBy { it.tag })
        model.addAttribute("responsavel", responsavel)
        model.addAttribute("bens", bens)
        model.addAttribute("hoje", LocalDate.now(relogio))
        return "termo-responsabilidade"
    }

    /** Erro de validação volta para a tela com a mensagem; deu certo, com a mensagem de sucesso. */
    private fun executar(redirect: RedirectAttributes, destino: String, acao: () -> String): String {
        try {
            redirect.addFlashAttribute("msg", acao())
        } catch (e: IllegalArgumentException) {
            redirect.addFlashAttribute("erro", e.message)
        } catch (e: NoSuchElementException) {
            redirect.addFlashAttribute("erro", e.message)
        }
        return "redirect:$destino"
    }
}
