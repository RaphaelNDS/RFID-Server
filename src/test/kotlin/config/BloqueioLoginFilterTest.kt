package org.example.config

import io.mockk.every
import io.mockk.mockk
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
        every { tentativas.tempoRestanteDeBloqueio("ana@x.com", "10.0.0.1") } returns null
        val chain = MockFilterChain()

        filtro.doFilter(login(), MockHttpServletResponse(), chain)

        assertThat(chain.request).isNotNull()
    }

    @Test
    fun `login bloqueado volta para a tela sem conferir a senha`() {
        every { tentativas.tempoRestanteDeBloqueio("ana@x.com", "10.0.0.1") } returns Duration.ofMinutes(12)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filtro.doFilter(login(), response, chain)

        assertThat(chain.request).isNull() // não chegou na verificação de senha
        assertThat(response.redirectedUrl).isEqualTo("/login?bloqueado=12")
    }

    @Test
    fun `minutos restantes sao arredondados para cima`() {
        every { tentativas.tempoRestanteDeBloqueio(any(), any()) } returns Duration.ofSeconds(30)
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
        verify(exactly = 0) { tentativas.tempoRestanteDeBloqueio(any(), any()) }
    }
}
