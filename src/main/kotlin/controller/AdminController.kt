package org.example.controller

import org.example.service.BackupService
import org.example.service.RfidService
import org.example.service.StatusService
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

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
    fun pendentes(model: Model): String {
        model.addAttribute("pendentes", rfidService.listarNaoCadastradas())
        return "admin-tags-pendentes"
    }

    @PreAuthorize("@authService.temPermissao('RFID','READ')")
    @GetMapping("/tags-cadastradas")
    fun cadastradas(model: Model): String {
        model.addAttribute("tags", rfidService.listarParaAdmin())
        return "admin-tags"
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
