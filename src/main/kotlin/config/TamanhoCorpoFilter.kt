package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

/**
 * Limite de tamanho do corpo na API dos coletores (JSON).
 *
 * O Tomcat não limita POST em JSON e o tamanho do lote (500 leituras, 50 erros, 500 itens de inventário) só é
 * conferido DEPOIS de o Jackson desserializar o corpo inteiro: um app em loop (ou quem tirou a chave de um aparelho)
 * mandando dezenas de MB podia esgotar a memória do servidor (-Xmx1g). Corpo com Content-Length acima do limite é
 * recusado com 413 sem ler nada; sem Content-Length (envio em partes), a leitura para ao passar do limite.
 */
class TamanhoCorpoFilter(private val maximoBytes: Long) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val informado = request.contentLengthLong
        if (informado > maximoBytes) {
            log.warn("Envio do coletor recusado antes de ler o corpo: {} {} com {} bytes (máximo {}; IP {})",
                request.method, request.requestURI, informado, maximoBytes, request.remoteAddr)
            response.setHeader("Connection", "close")
            response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.writer.write("""{"status":413,"mensagem":"Envio grande demais: o máximo é ${maximoBytes / 1_048_576} MB"}""")
            return
        }
        // Sem Content-Length: conta o que for lido (o Jackson recebe erro de leitura e a requisição vira 400)
        chain.doFilter(if (informado < 0) ComLimite(request, maximoBytes) else request, response)
    }

    private class ComLimite(request: HttpServletRequest, private val maximo: Long) : HttpServletRequestWrapper(request) {
        private var corpo: ServletInputStream? = null

        override fun getInputStream(): ServletInputStream {
            corpo?.let { return it }
            val original = super.getInputStream()
            return object : ServletInputStream() {
                private var lidos = 0L

                private fun contar(n: Int): Int {
                    if (n > 0) {
                        lidos += n
                        if (lidos > maximo) throw IOException("Corpo da requisição passou de $maximo bytes")
                    }
                    return n
                }

                override fun read(): Int {
                    val b = original.read()
                    if (b >= 0) contar(1)
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int = contar(original.read(b, off, len))
                override fun isFinished() = original.isFinished
                override fun isReady() = original.isReady
                override fun setReadListener(listener: ReadListener?) = original.setReadListener(listener)
            }.also { corpo = it }
        }
    }
}
