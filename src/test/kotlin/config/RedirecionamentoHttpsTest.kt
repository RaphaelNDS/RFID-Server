package org.example.config

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.example.service.CertificadoHttps
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** Porta HTTP com o HTTPS ligado: telas vão para o HTTPS, a API recebe 426 e nada passa adiante por HTTP. */
class RedirecionamentoHttpsTest {

    private val certificado = mockk<CertificadoHttps> {
        every { nomeConfiavel(any()) } answers { if (firstArg<String>() == "rfid-servidor") "rfid-servidor" else "192.168.0.10" }
    }
    private val filtro = HttpsConfig(true, 8443, 8082, certificado).redirecionamentoHttps().filter

    private fun pedir(caminho: String, seguro: Boolean = false, host: String = "rfid-servidor", query: String? = null):
        Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest("GET", caminho).apply {
            isSecure = seguro
            serverName = host
            queryString = query
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filtro.doFilter(request, response, chain)
        return response to chain
    }

    @Test
    fun `tela pedida por HTTP vai para o HTTPS com o mesmo caminho e a mesma busca`() {
        val (response, chain) = pedir("/admin/tags-cadastradas", query = "busca=e200")

        assertThat(response.redirectedUrl).isEqualTo("https://rfid-servidor:8443/admin/tags-cadastradas?busca=e200")
        assertThat(chain.request).isNull() // não seguiu para o resto da aplicação
    }

    @Test
    fun `API por HTTP recebe 426 explicando o endereco HTTPS`() {
        val (response, chain) = pedir("/api/android/leituras/lote")

        assertThat(response.status).isEqualTo(426)
        assertThat(response.contentAsString).contains("https://rfid-servidor:8443")
        assertThat(response.redirectedUrl).isNull()
        assertThat(chain.request).isNull()
    }

    @Test
    fun `host forjado nao vai para o redirecionamento`() {
        val (response, _) = pedir("/login", host = "site-malicioso.com")

        assertThat(response.redirectedUrl).isEqualTo("https://192.168.0.10:8443/login")
    }

    @Test
    fun `pedido ja em HTTPS segue normalmente`() {
        val (response, chain) = pedir("/login", seguro = true)

        assertThat(chain.request).isNotNull()
        assertThat(response.redirectedUrl).isNull()
        assertThat(response.status).isEqualTo(200)
    }
}
