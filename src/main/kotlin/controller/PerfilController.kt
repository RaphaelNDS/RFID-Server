package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.example.config.TrocaSenhaObrigatoriaFilter
import org.example.security.UserPrincipal
import org.example.service.CustomUserDetailsService
import org.example.service.UsuarioService
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

@Controller
@RequestMapping("/perfil")
class PerfilController(
    private val usuarioService: UsuarioService,
    private val userDetailsService: CustomUserDetailsService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Mesmo repositório que o login por formulário usa para guardar a autenticação na sessão
    private val contextoNaSessao = HttpSessionSecurityContextRepository()

    @GetMapping
    fun perfil(): String = "perfil"

    @GetMapping("/senha")
    fun senha(session: HttpSession, model: Model): String {
        model.addAttribute("trocaObrigatoria", trocaObrigatoria(session))
        return "perfil-senha"
    }

    @PostMapping("/senha")
    fun alterarSenha(
        @AuthenticationPrincipal usuario: UserPrincipal,
        @RequestParam senhaAtual: String,
        @RequestParam novaSenha: String,
        @RequestParam confirmarSenha: String,
        session: HttpSession,
        model: Model
    ): String {

        return try {
            usuarioService.alterarSenha(usuario.id, senhaAtual, novaSenha, confirmarSenha)
            // Senha temporária ou fraca substituída: o sistema volta a ficar liberado nesta sessão
            session.removeAttribute(TrocaSenhaObrigatoriaFilter.ATRIBUTO_SESSAO)
            session.removeAttribute(TrocaSenhaObrigatoriaFilter.ATRIBUTO_MOTIVO)
            "redirect:/perfil?senhaAlterada"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            model.addAttribute("trocaObrigatoria", trocaObrigatoria(session))
            "perfil-senha"
        }
    }

    // Aviso de senha temporária; a senha fraca tem o próprio aviso (?fraca)
    private fun trocaObrigatoria(session: HttpSession) =
        session.getAttribute(TrocaSenhaObrigatoriaFilter.ATRIBUTO_SESSAO) == true &&
            session.getAttribute(TrocaSenhaObrigatoriaFilter.ATRIBUTO_MOTIVO) != TrocaSenhaObrigatoriaFilter.MOTIVO_FRACA

    @GetMapping("/email")
    fun email(): String = "perfil-email"

    @PostMapping("/email")
    fun alterarEmail(
        @AuthenticationPrincipal usuario: UserPrincipal,
        @RequestParam novoEmail: String,
        @RequestParam senhaAtual: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model
    ): String {

        val emailGravado = try {
            usuarioService.alterarEmail(usuario.id, novoEmail, senhaAtual)
        } catch (e: IllegalArgumentException) {
            model.addAttribute("erro", e.message)
            model.addAttribute("novoEmail", novoEmail)
            return "perfil-email"
        }

        atualizarSessao(emailGravado, request, response)

        return "redirect:/perfil?emailAlterado"
    }

    /**
     * O email é o login: a sessão guarda o usuário com o email antigo.
     * Recarrega o usuário com o email novo e grava na sessão, para o usuário continuar logado
     * e as próximas operações (como trocar a senha) enxergarem o email certo.
     */
    private fun atualizarSessao(email: String, request: HttpServletRequest, response: HttpServletResponse) {
        val atual = SecurityContextHolder.getContext().authentication
        val usuarioAtualizado = userDetailsService.loadUserByUsername(email)

        val novaAutenticacao = UsernamePasswordAuthenticationToken.authenticated(
            usuarioAtualizado, null, usuarioAtualizado.authorities
        ).apply { details = atual?.details }

        val contexto = SecurityContextHolder.createEmptyContext().apply { authentication = novaAutenticacao }
        SecurityContextHolder.setContext(contexto)
        contextoNaSessao.saveContext(contexto, request, response)

        log.info("Sessão atualizada com o novo email: {}", email)
    }
}
