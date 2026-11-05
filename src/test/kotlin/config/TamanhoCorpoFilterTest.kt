package org.example.config

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.IOException

class TamanhoCorpoFilterTest {

    private val filtro = TamanhoCorpoFilter(maximoBytes = 1_000)

    private fun requisicao(corpo: ByteArray, informarTamanho: Boolean): MockHttpServletRequest {
        val request = object : MockHttpServletRequest("POST", "/api/android/leituras/lote") {
            // MockHttpServletRequest sempre informa o tamanho do conteúdo; -1 simula envio em partes (chunked)
            override fun getContentLengthLong() = if (informarTamanho) contentAsByteArray.size.toLong() else -1L
            override fun getContentLength() = if (informarTamanho) contentAsByteArray.size else -1
        }
        request.contentType = "application/json"
        request.setContent(corpo)
        return request
    }

    @Test
    fun `corpo dentro do limite segue para a API`() {
        val chain = MockFilterChain()
        filtro.doFilter(requisicao(ByteArray(900), informarTamanho = true), MockHttpServletResponse(), chain)

        assertThat(chain.request).isNotNull()
    }

    @Test
    fun `corpo acima do limite e recusado com 413 sem ler nada`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filtro.doFilter(requisicao(ByteArray(5_000), informarTamanho = true), response, chain)

        assertThat(response.status).isEqualTo(413)
        assertThat(response.contentAsString).contains("Envio grande demais")
        assertThat(chain.request).isNull()
    }

    @Test
    fun `sem Content-Length a leitura para ao passar do limite`() {
        val chain = MockFilterChain()
        filtro.doFilter(requisicao(ByteArray(5_000), informarTamanho = false), MockHttpServletResponse(), chain)

        val corpo = (chain.request as HttpServletRequest).inputStream
        assertThatThrownBy { corpo.readAllBytes() }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `sem Content-Length e dentro do limite le o corpo inteiro`() {
        val chain = MockFilterChain()
        filtro.doFilter(requisicao(ByteArray(800) { 7 }, informarTamanho = false), MockHttpServletResponse(), chain)

        assertThat((chain.request as HttpServletRequest).inputStream.readAllBytes()).hasSize(800)
    }
}
