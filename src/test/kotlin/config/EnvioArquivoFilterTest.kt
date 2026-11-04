package org.example.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

class EnvioArquivoFilterTest {

    /** Requisição que falha se alguém tentar ler o corpo: prova que a recusa acontece sem ler o arquivo. */
    private class SemLerCorpo(metodo: String, caminho: String) : MockHttpServletRequest(metodo, caminho) {
        var corpoLido = false
        override fun getParameter(name: String): String? { corpoLido = true; return super.getParameter(name) }
        override fun getInputStream() = super.getInputStream().also { corpoLido = true }
        override fun getParts() = super.getParts().also { corpoLido = true }
    }

    private fun multipart(metodo: String = "POST", caminho: String = "/admin/app") =
        SemLerCorpo(metodo, caminho).apply {
            contentType = "multipart/form-data; boundary=x"
            setContent(ByteArray(10_000))
        }

    private fun logado(vararg autoridades: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated("ana@x.com", null, autoridades.map(::SimpleGrantedAuthority))
    }

    @AfterEach
    fun limpar() = SecurityContextHolder.clearContext()

    private fun passar(filtro: EnvioArquivoFilter, request: MockHttpServletRequest): Pair<MockHttpServletResponse, MockFilterChain> {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filtro.doFilter(request, response, chain)
        return response to chain
    }

    @Test
    fun `anonimo mandando arquivo para o login e recusado sem ler o corpo`() {
        val request = multipart(caminho = "/login")

        val (response, chain) = passar(EnvioArquivoFilter(), request)

        assertThat(response.status).isEqualTo(403)
        assertThat(chain.request).isNull()
        assertThat(request.corpoLido).isFalse()
    }

    @Test
    fun `arquivo para o app sem ser ADMIN e recusado sem ler o corpo`() {
        logado("ROLE_GESTOR", "RFID_CREATE")
        val request = multipart()

        val (response, _) = passar(EnvioArquivoFilter(), request)

        assertThat(response.status).isEqualTo(403)
        assertThat(request.corpoLido).isFalse()
    }

    @Test
    fun `ADMIN enviando o APK passa`() {
        logado("ROLE_ADMIN")

        val (_, chain) = passar(EnvioArquivoFilter(), multipart())

        assertThat(chain.request).isNotNull()
    }

    @Test
    fun `ADMIN mandando arquivo para outra rota e recusado`() {
        logado("ROLE_ADMIN")

        val (response, chain) = passar(EnvioArquivoFilter(), multipart(caminho = "/admin/cadastro/salvar"))

        assertThat(response.status).isEqualTo(403)
        assertThat(chain.request).isNull()
    }

    @Test
    fun `formulario comum nao e afetado`() {
        val request = MockHttpServletRequest("POST", "/login").apply { contentType = "application/x-www-form-urlencoded" }

        val (_, chain) = passar(EnvioArquivoFilter(), request)

        assertThat(chain.request).isNotNull()
    }

    @Test
    fun `API dos coletores nao aceita arquivo nenhum`() {
        logado("ROLE_ADMIN")

        val (response, _) = passar(EnvioArquivoFilter(permitido = null), multipart(caminho = "/api/android/leituras/lote"))

        assertThat(response.status).isEqualTo(403)
    }
}
