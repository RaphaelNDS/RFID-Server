package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Autentica os coletores Android pela chave enviada no header [HEADER].
 *
 * As chaves vêm de `rfid.dispositivos.chaves` (variável RFID_CHAVES_DISPOSITIVOS), no formato
 * `nome:chave` separados por vírgula. O nome vira o "usuário" da requisição, então aparece nos
 * logs (ex.: quem registrou uma tag não cadastrada).
 *
 * NÃO é um @Component de propósito: se fosse, o Spring Boot registraria o filtro para todas as
 * requisições. Ele é usado só na cadeia de segurança de /api/android (SecurityConfig).
 */
class ChaveDispositivoFilter(chavesConfiguradas: String) : OncePerRequestFilter() {

    companion object {
        const val HEADER = "X-Chave-Dispositivo"
        const val ROLE = "DISPOSITIVO"
        private const val TAMANHO_MINIMO_CHAVE = 16
    }

    private val log = LoggerFactory.getLogger(javaClass)

    private val dispositivos: Map<String, ByteArray> = lerChaves(chavesConfiguradas)

    init {
        if (dispositivos.isEmpty()) {
            log.error(
                "Nenhuma chave de dispositivo configurada (rfid.dispositivos.chaves / RFID_CHAVES_DISPOSITIVOS): " +
                    "o app Android não conseguirá acessar /api/android/**"
            )
        } else {
            log.info("Chaves de dispositivo carregadas para {} coletor(es): {}", dispositivos.size, dispositivos.keys.joinToString())
        }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val chave = request.getHeader(HEADER)

        if (chave.isNullOrBlank()) {
            log.warn("Requisição de dispositivo sem chave: {} {} (IP: {})", request.method, request.requestURI, request.remoteAddr)
            recusar(response, "Chave do dispositivo não informada")
            return
        }

        val nome = identificar(chave)

        if (nome == null) {
            log.warn("Requisição de dispositivo com chave inválida: {} {} (IP: {})", request.method, request.requestURI, request.remoteAddr)
            recusar(response, "Chave do dispositivo inválida")
            return
        }

        val autenticacao = UsernamePasswordAuthenticationToken.authenticated(
            nome, null, listOf(SimpleGrantedAuthority("ROLE_$ROLE"))
        )
        SecurityContextHolder.getContext().authentication = autenticacao

        log.debug("Dispositivo {} autenticado: {} {}", nome, request.method, request.requestURI)

        chain.doFilter(request, response)
    }

    // Compara com todas as chaves em tempo constante, para não dar pistas da chave pelo tempo de resposta
    private fun identificar(chave: String): String? {
        val recebida = chave.toByteArray()
        var encontrado: String? = null

        for ((nome, esperada) in dispositivos) {
            if (MessageDigest.isEqual(recebida, esperada)) encontrado = nome
        }

        return encontrado
    }

    private fun recusar(response: HttpServletResponse, mensagem: String) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"status":401,"mensagem":"$mensagem"}""")
    }

    private fun lerChaves(config: String): Map<String, ByteArray> {
        val resultado = linkedMapOf<String, ByteArray>()

        config.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { item ->
                val partes = item.split(':', limit = 2).map { it.trim() }
                val nome = partes.getOrNull(0).orEmpty()
                val chave = partes.getOrNull(1).orEmpty()

                if (nome.isEmpty() || chave.isEmpty()) {
                    log.warn("Entrada de chave de dispositivo ignorada: formato esperado nome:chave (recebido '{}')", nome.ifEmpty { "?" })
                    return@forEach
                }

                if (chave.length < TAMANHO_MINIMO_CHAVE) {
                    log.warn("A chave do dispositivo {} tem menos de {} caracteres: use uma chave mais longa", nome, TAMANHO_MINIMO_CHAVE)
                }

                if (resultado.put(nome, chave.toByteArray()) != null) {
                    log.warn("Dispositivo {} aparece mais de uma vez nas chaves: vale a última", nome)
                }
            }

        return resultado
    }
}
