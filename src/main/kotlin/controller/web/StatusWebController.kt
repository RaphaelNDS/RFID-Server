package org.example.controller.web

import org.example.service.AlertaService
import org.example.service.StatusService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class StatusWebController(
    private val service: StatusService,
    private val alertas: AlertaService
) {

    // Só ADMIN, como /api/status: mostra o endereço do banco, a situação dos backups e a instrução docker exec
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/status")
    fun status(model: Model): String {
        model.addAttribute("info", service.getStatus())
        return "status"
    }

    /** Email de teste dos avisos (1.5.1): manda na hora e mostra o erro do SMTP, se houver. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/status/email-teste")
    fun emailTeste(auth: Authentication, redirect: RedirectAttributes): String {
        alertas.enviarTeste(auth.name)
            .onSuccess { redirect.addFlashAttribute("msg", "Email de teste enviado para ${it.joinToString()}. Confira a caixa de entrada (e o lixo eletrônico).") }
            .onFailure { redirect.addFlashAttribute("erro", "O email de teste não foi enviado: ${it.message}") }
        return "redirect:/status"
    }

    /** Mensagem de teste pelo Telegram (1.20.0). */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/status/telegram-teste")
    fun telegramTeste(auth: Authentication, redirect: RedirectAttributes): String {
        alertas.enviarTesteTelegram(auth.name)
            .onSuccess { redirect.addFlashAttribute("msg", "Mensagem de teste enviada pelo Telegram para $it conversa(s).") }
            .onFailure { redirect.addFlashAttribute("erro", "A mensagem de teste não foi enviada: ${it.message}") }
        return "redirect:/status"
    }

    /** Quem mandou mensagem ao robô do Telegram (1.20.0): mostra o número da conversa para pôr em RFID_TELEGRAM_CHATS. */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/status/telegram-conversas")
    fun telegramConversas(redirect: RedirectAttributes): String {
        alertas.descobrirConversasTelegram()
            .onSuccess { lista ->
                redirect.addFlashAttribute("msg", if (lista.isEmpty())
                    "Nenhuma conversa encontrada. Mande uma mensagem ao robô no Telegram (ou ponha ele num grupo e escreva algo) e tente de novo."
                else "Conversas com o robô: " + lista.joinToString("; ") { "${it.nome} (${it.tipo}) = ${it.id}" } +
                    ". Ponha os números em RFID_TELEGRAM_CHATS, separados por vírgula, e reinicie o servidor.")
            }
            .onFailure { redirect.addFlashAttribute("erro", "Não deu para consultar o Telegram: ${it.message}") }
        return "redirect:/status"
    }
}
