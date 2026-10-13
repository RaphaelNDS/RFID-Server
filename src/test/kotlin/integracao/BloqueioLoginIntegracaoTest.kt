package org.example.integracao

import org.example.request.SetupAdminRequest
import org.example.service.UsuarioService
import org.example.suporte.IntegracaoBase
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl

/**
 * Login de verdade pelo formulário. O contador de tentativas é compartilhado por toda a aplicação de teste,
 * então cada teste usa email e IP próprios para não interferir nos outros.
 */
class BloqueioLoginIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var usuarios: UsuarioService

    private fun entrar(email: String, senha: String, ip: String): ResultActions =
        mvc.perform(post("/login").with(csrf()).with { it.remoteAddr = ip; it }
            .param("username", email).param("password", senha))

    @Test
    fun `cinco senhas erradas bloqueiam e nem a senha certa entra`() {
        usuarios.criarAdminInicial(SetupAdminRequest("Ana", "bloqueio1@x.com", "Kx9mPq2v"))

        repeat(5) { entrar("bloqueio1@x.com", "errada", "10.1.0.1").andExpect(redirectedUrl("/login?error")) }

        entrar("bloqueio1@x.com", "Kx9mPq2v", "10.1.0.1").andExpect(redirectedUrl("/login?bloqueado=15"))
    }

    @Test
    fun `bloqueio do email vale de qualquer IP`() {
        usuarios.criarAdminInicial(SetupAdminRequest("Ana", "bloqueio2@x.com", "Kx9mPq2v"))

        repeat(5) { entrar("bloqueio2@x.com", "errada", "10.2.0.1") }

        entrar("bloqueio2@x.com", "Kx9mPq2v", "10.2.0.99").andExpect(redirectedUrl("/login?bloqueado=15"))
    }

    @Test
    fun `menos de cinco erros e depois a senha certa entra normalmente`() {
        usuarios.criarAdminInicial(SetupAdminRequest("Ana", "bloqueio3@x.com", "Kx9mPq2v"))

        repeat(4) { entrar("bloqueio3@x.com", "errada", "10.3.0.1") }

        entrar("bloqueio3@x.com", "Kx9mPq2v", "10.3.0.1").andExpect(redirectedUrl("/redirect"))
    }

    @Test
    fun `email inexistente e bloqueado igual, sem revelar que a conta nao existe`() {
        repeat(5) { entrar("ninguem@x.com", "qualquer", "10.4.0.1").andExpect(redirectedUrl("/login?error")) }

        entrar("ninguem@x.com", "qualquer", "10.4.0.1").andExpect(redirectedUrl("/login?bloqueado=15"))
    }

    @Test
    fun `tela de login explica o bloqueio`() {
        // Com o banco vazio /login manda para o /setup; precisa existir um usuário
        usuarios.criarAdminInicial(SetupAdminRequest("Ana", "bloqueio5@x.com", "Kx9mPq2v"))

        mvc.perform(get("/login").param("bloqueado", "7"))
            .andExpect(content().string(containsString("Muitas tentativas sem sucesso")))
            .andExpect(content().string(containsString("7 minuto(s)")))
    }
}
