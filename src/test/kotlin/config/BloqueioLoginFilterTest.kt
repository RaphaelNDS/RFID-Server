package org.example.config

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.example.service.TentativasLoginService
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration

class BloqueioLoginFilterTest {

    private val tentativas = mockk<TentativasLoginService>()
    private val filtro = BloqueioLoginFilter(tentativas)

    private fun login(email: String = "ana@x.com") = MockHttpServletRequest("POST", "/login").apply {
        requestURI = "/login"
        remoteAddr = "10.0.0.1"
        addParameter("username", email)
    }

    @Test
    fun `login liberado segue normalmente`() {
        every { tentativas.consultarBloqueio("ana@x.com", "10.0.0.1") } returns null
        val chain = MockFilterChain()

        filtro.doFilter(login(), MockHttpServletResponse(), chain)

        assertThat(chain.request).isNotNull()
    }

    @Test
    fun `login bloqueado volta para a tela sem conferir a senha`() {
        every { tentativas.consultarBloqueio("ana@x.com", "10.0.0.1") } returns
            TentativasLoginService.Bloqueio(Duration.ofMinutes(12), primeiraRecusa = true)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filtro.doFilter(login(), response, chain)

        assertThat(chain.request).isNull() // não chegou na verificação de senha
        assertThat(response.redirectedUrl).isEqualTo("/login?bloqueado=12")
    }

    @Test
    fun `minutos restantes sao arredondados para cima`() {
        every { tentativas.consultarBloqueio(any(), any()) } returns
            TentativasLoginService.Bloqueio(Duration.ofSeconds(30), primeiraRecusa = false)
        val response = MockHttpServletResponse()

        filtro.doFilter(login(), response, MockFilterChain())

        assertThat(response.redirectedUrl).isEqualTo("/login?bloqueado=1")
    }

    @Test
    fun `outras requisicoes nem consultam o bloqueio`() {
        val chain = MockFilterChain()
        val tela = MockHttpServletRequest("GET", "/login")

        filtro.doFilter(tela, MockHttpServletResponse(), chain)

        assertThat(chain.request).isNotNull()
        verify(exactly = 0) { tentativas.consultarBloqueio(any(), any()) }
    }

    @Test
    fun `email digitado e normalizado antes de consultar o bloqueio`() {
        every { tentativas.consultarBloqueio("ana@x.com", "10.0.0.1") } returns null
        val chain = MockFilterChain()

        filtro.doFilter(login(" ANA@x.com\r\n"), MockHttpServletResponse(), chain)

        assertThat(chain.request).isNotNull()
        verify { tentativas.consultarBloqueio("ana@x.com", "10.0.0.1") }
    }

    @Test
    fun `email gigante e recusado como falha de login comum sem chegar a senha`() {
        val gigante = "a".repeat(1_000_000) + "@x.com"
        every { tentativas.consultarBloqueio(any(), any()) } returns null
        every { tentativas.registrarFalha(any(), any()) } just runs
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filtro.doFilter(login(gigante), response, chain)

        assertThat(chain.request).isNull()
        assertThat(response.redirectedUrl).isEqualTo("/login?error")
        // Contadores e log só recebem a forma cortada (255 caracteres)
        verify { tentativas.consultarBloqueio(match { it.length == 255 }, "10.0.0.1") }
        verify { tentativas.registrarFalha(match { it.length == 255 }, "10.0.0.1") }
    }

    @Test
    fun `email de 255 caracteres ainda segue para a conferencia da senha`() {
        every { tentativas.consultarBloqueio(any(), any()) } returns null
        val chain = MockFilterChain()

        filtro.doFilter(login("a".repeat(249) + "@x.com"), MockHttpServletResponse(), chain)

        assertThat(chain.request).isNotNull()
    }
}
