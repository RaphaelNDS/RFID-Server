package org.example.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class ChaveDispositivoFilterTest {

    private val chaves = "coletor-01:chave-longa-numero-um, coletor-02:chave-longa-numero-dois"

    @AfterEach
    fun limparContexto() = SecurityContextHolder.clearContext()

    private fun executar(filtro: ChaveDispositivoFilter, chave: String?): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest("GET", "/api/android/ping")
        if (chave != null) request.addHeader(ChaveDispositivoFilter.HEADER, chave)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filtro.doFilter(request, response, chain)
        return response to chain
    }

    @Test
    fun `sem header responde 401 e nao segue a cadeia`() {
        val (response, chain) = executar(ChaveDispositivoFilter(chaves), null)

        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentAsString).contains("Chave do dispositivo não informada")
        assertThat(chain.request).isNull()
    }

    @Test
    fun `chave em branco e tratada como ausente`() {
        val (response, chain) = executar(ChaveDispositivoFilter(chaves), "   ")

        assertThat(response.status).isEqualTo(401)
        assertThat(chain.request).isNull()
    }

    @Test
    fun `chave errada responde 401`() {
        val (response, chain) = executar(ChaveDispositivoFilter(chaves), "chave-que-nao-existe")

        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentType).startsWith("application/json")
        assertThat(response.contentAsString).contains("Chave do dispositivo inválida")
        assertThat(chain.request).isNull()
    }

    @Test
    fun `chave certa autentica o dispositivo pelo nome e segue a cadeia`() {
        val (response, chain) = executar(ChaveDispositivoFilter(chaves), "chave-longa-numero-dois")

        assertThat(response.status).isEqualTo(200)
        assertThat(chain.request).isNotNull()

        val auth = SecurityContextHolder.getContext().authentication
        assertThat(auth.name).isEqualTo("coletor-02")
        assertThat(auth.isAuthenticated).isTrue()
        assertThat(auth.authorities.map { it.authority }).containsExactly("ROLE_DISPOSITIVO")
    }

    @Test
    fun `chave e comparada inteira, nao por prefixo`() {
        val (response, _) = executar(ChaveDispositivoFilter(chaves), "chave-longa-numero")

        assertThat(response.status).isEqualTo(401)
    }

    @Test
    fun `sem chaves configuradas ninguem entra`() {
        val (response, chain) = executar(ChaveDispositivoFilter(""), "qualquer-coisa-aqui-1234")

        assertThat(response.status).isEqualTo(401)
        assertThat(chain.request).isNull()
    }

    @Test
    fun `entradas mal formadas sao ignoradas sem invalidar as boas`() {
        val filtro = ChaveDispositivoFilter("sem-dois-pontos, :sem-nome, sem-chave:, coletor-ok:chave-valida-0123456789")

        assertThat(executar(filtro, "chave-valida-0123456789").first.status).isEqualTo(200)
        assertThat(executar(filtro, "sem-nome").first.status).isEqualTo(401)
    }

    @Test
    fun `chave pode conter dois pontos`() {
        val filtro = ChaveDispositivoFilter("coletor-01:parte1:parte2:parte3")

        executar(filtro, "parte1:parte2:parte3")

        assertThat(SecurityContextHolder.getContext().authentication.name).isEqualTo("coletor-01")
    }

    @Test
    fun `dispositivo repetido vale a ultima chave`() {
        val filtro = ChaveDispositivoFilter("coletor-01:chave-antiga-0123456789,coletor-01:chave-nova-0123456789")

        assertThat(executar(filtro, "chave-antiga-0123456789").first.status).isEqualTo(401)
        assertThat(executar(filtro, "chave-nova-0123456789").first.status).isEqualTo(200)
    }
}
