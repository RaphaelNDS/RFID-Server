package org.example.controller

import org.example.service.InventarioCiclicoService
import org.example.service.PainelService
import org.example.util.FormatoBr
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.time.Clock
import java.time.LocalDateTime

/**
 * Painel para a TV do depósito (1.28.0): o painel do dia em tela cheia, letras grandes e sem menu, que se atualiza
 * sozinho a cada minuto. Abre com um usuário que vê a locação (Locação · Ver); a atualização periódica mantém a sessão
 * viva, e se ela cair (servidor reiniciado, usuário desativado) a TV mostra o aviso para entrar de novo.
 */
@Controller
@RequestMapping("/admin/painel-tv")
class PainelTvWebController(
    private val painel: PainelService,
    private val relogio: Clock,
    private val ciclico: InventarioCiclicoService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping
    fun tela(model: Model): String {
        log.info("Painel da TV aberto por {}", SecurityContextHolder.getContext().authentication?.name)
        preencher(model)
        return "painel-tv"
    }

    /** Só o miolo, buscado pela própria tela a cada minuto. */
    @PreAuthorize("@authService.temPermissao('LOCACAO','READ')")
    @GetMapping("/conteudo")
    fun conteudo(model: Model): String {
        preencher(model)
        return "painel-tv :: conteudo"
    }

    private fun preencher(model: Model) {
        model.addAttribute("p", painel.painel())
        model.addAttribute("cicloSemana", ciclico?.locaisDaSemana())
        model.addAttribute("atualizadoEm", LocalDateTime.now(relogio))
        model.addAttribute("fmt", FormatoBr)
    }
}
