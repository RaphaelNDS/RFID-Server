package org.example.controller

import org.example.service.BackupService
import org.example.service.RfidService
import org.example.service.StatusService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.util.UriComponentsBuilder

@PreAuthorize("hasRole('ADMIN')")
@Controller
@RequestMapping("/admin")
class AdminController(
    private val rfidService: RfidService,
    private val statusService: StatusService,
    private val backupService: BackupService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun dashboard(model: Model): String {
        model.addAttribute("info", statusService.getStatus())
        return "admin"
    }

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/tags-pendentes")
    fun pendentes(
        @RequestParam(required = false) busca: String?,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {
        // Na tela as páginas começam em 1; no serviço, em 0
        val resultado = rfidService.listarNaoCadastradas(busca, pagina - 1)
        model.addAttribute("pendentes", resultado.content)
        paginacao(model, resultado, "/admin/tags-pendentes", busca)
        return "admin-tags-pendentes"
    }

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/tags-cadastradas")
    fun cadastradas(
        @RequestParam(required = false) busca: String?,
        @RequestParam(defaultValue = "1") pagina: Int,
        model: Model
    ): String {
        val resultado = rfidService.listarParaAdmin(busca, pagina - 1)
        model.addAttribute("tags", resultado.content)
        paginacao(model, resultado, "/admin/tags-cadastradas", busca)
        return "admin-tags"
    }

    /** Total, página atual e links de anterior/próxima (que mantêm a busca) para as telas paginadas. */
    private fun paginacao(model: Model, resultado: Page<*>, caminho: String, busca: String?) {
        val termo = busca?.trim().orEmpty()
        fun link(numero: Int) = UriComponentsBuilder.fromPath(caminho)
            .apply { if (termo.isNotEmpty()) queryParam("busca", termo) }
            .queryParam("pagina", numero).encode().toUriString()

        val paginaAtual = resultado.number + 1
        model.addAttribute("busca", termo)
        model.addAttribute("total", resultado.totalElements)
        model.addAttribute("paginaAtual", paginaAtual)
        model.addAttribute("totalPaginas", resultado.totalPages.coerceAtLeast(1))
        model.addAttribute("linkAnterior", if (resultado.hasPrevious()) link(paginaAtual - 1) else null)
        model.addAttribute("linkProxima", if (resultado.hasNext()) link(paginaAtual + 1) else null)
    }

    // Backup manual (só ADMIN, pela regra da classe), ex.: antes de atualizar o sistema
    @PostMapping("/backup")
    fun fazerBackup(auth: Authentication, redirect: RedirectAttributes): String {
        log.info("Backup manual solicitado por {}", auth.name)

        try {
            val arquivo = backupService.fazerBackup("manual, por ${auth.name}")
            redirect.addFlashAttribute("msg", "Backup concluído: ${arquivo.fileName}")
        } catch (e: IllegalStateException) {
            redirect.addFlashAttribute("erro", e.message)
        }

        return "redirect:/status"
    }
}
