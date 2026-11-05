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
class ChaveDispositivoFilter(
    chavesConfiguradas: String,
    private val relogio: java.time.Clock = java.time.Clock.systemDefaultZone(),
    /** Chaves erradas por IP, dentro de [JANELA_CHAVES_ERRADAS], até a recusa com 429. */
    private val maximoChavesErradas: Int = MAXIMO_CHAVES_ERRADAS
) : OncePerRequestFilter() {

    companion object {
        const val HEADER = "X-Chave-Dispositivo"
        const val ROLE = "DISPOSITIVO"
        private const val TAMANHO_MINIMO_CHAVE = 16

        /** Chaves erradas por IP até a recusa com 429 (quem tenta adivinhar a chave de um coletor). */
        const val MAXIMO_CHAVES_ERRADAS = 20
        val JANELA_CHAVES_ERRADAS: java.time.Duration = java.time.Duration.ofMinutes(15)

        /** Chave que funcionou de um IP há menos que isto continua passando com o IP bloqueado (NAT). */
        val JANELA_CHAVE_CONHECIDA: java.time.Duration = java.time.Duration.ofHours(24)

        /** Texto da chave de exemplo (application.properties.exemplo): copiada sem trocar, a chave é pública. */
        private val CHAVE_DE_EXEMPLO = Regex("(?i)troque")
    }

    /** IP -> horários das chaves erradas dentro da janela. */
    private val errosPorIp = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<java.time.Instant>>()

    /** "coletor@IP" -> última vez que a chave daquele coletor funcionou daquele IP (passa mesmo com o IP bloqueado). */
    private val sucessosPorColetorIp = java.util.concurrent.ConcurrentHashMap<String, java.time.Instant>()

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
        val ip = request.remoteAddr ?: "?"

        val nome = if (chave.isNullOrBlank()) null else identificar(chave)

        // IP bloqueado: só passa a chave que JÁ funcionou deste IP nas últimas 24 h. Vários coletores saem pelo mesmo
        // IP do roteador (NAT) e um só com a chave velha bloqueava também os de chave certa por 15 min. Liberar
        // qualquer chave válida mostraria a quem tenta adivinhar quando ele acertou, por isso só a já conhecida
        if (bloqueado(ip) && (nome == null || !conhecidoNoIp(nome, ip))) {
            log.warn("Requisição de dispositivo recusada: IP {} errou a chave {} vezes em {} min ({} {})", ip, maximoChavesErradas,
                JANELA_CHAVES_ERRADAS.toMinutes(), request.method, request.requestURI)
            recusar(response, "Muitas tentativas com chave inválida. Aguarde alguns minutos.", HttpStatus.TOO_MANY_REQUESTS)
            return
        }

        if (nome == null) {
            if (chave.isNullOrBlank()) {
                log.warn("Requisição de dispositivo sem chave: {} {} (IP: {})", request.method, request.requestURI, ip)
                recusar(response, "Chave do dispositivo não informada")
                return
            }
            log.warn("Requisição de dispositivo com chave inválida: {} {} (IP: {})", request.method, request.requestURI, ip)
            registrarErro(ip)
            recusar(response, "Chave do dispositivo inválida")
            return
        }

        val autenticacao = UsernamePasswordAuthenticationToken.authenticated(
            nome, null, listOf(SimpleGrantedAuthority("ROLE_$ROLE"))
        )
        SecurityContextHolder.getContext().authentication = autenticacao
        registrarSucesso(nome, ip)

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

    private fun bloqueado(ip: String): Boolean {
        val erros = errosPorIp[ip] ?: return false
        synchronized(erros) {
            descartarAntigos(erros)
            return erros.size >= maximoChavesErradas
        }
    }

    private fun registrarErro(ip: String) {
        // Memória limitada: com muitos IPs diferentes (varredura), começa de novo
        if (errosPorIp.size > 10_000) errosPorIp.clear()
        val erros = errosPorIp.computeIfAbsent(ip) { ArrayDeque() }
        synchronized(erros) {
            descartarAntigos(erros)
            erros.addLast(relogio.instant())
            if (erros.size == maximoChavesErradas) {
                log.warn("IP {} bloqueado por até {} min na API dos coletores: {} chaves inválidas seguidas", ip,
                    JANELA_CHAVES_ERRADAS.toMinutes(), maximoChavesErradas)
            }
        }
    }

    private fun registrarSucesso(nome: String, ip: String) {
        // Memória limitada: são poucos coletores, mas o IP de cada um pode mudar (DHCP)
        if (sucessosPorColetorIp.size > 10_000) sucessosPorColetorIp.clear()
        sucessosPorColetorIp["$nome@$ip"] = relogio.instant()
    }

    private fun conhecidoNoIp(nome: String, ip: String): Boolean {
        val ultimo = sucessosPorColetorIp["$nome@$ip"] ?: return false
        return ultimo.isAfter(relogio.instant().minus(JANELA_CHAVE_CONHECIDA))
    }

    private fun descartarAntigos(erros: ArrayDeque<java.time.Instant>) {
        val limite = relogio.instant().minus(JANELA_CHAVES_ERRADAS)
        while (erros.firstOrNull()?.isBefore(limite) == true) erros.removeFirst()
    }

    private fun recusar(response: HttpServletResponse, mensagem: String, status: HttpStatus = HttpStatus.UNAUTHORIZED) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"status":${status.value()},"mensagem":"$mensagem"}""")
    }

    private fun lerChaves(config: String): Map<String, ByteArray> {
        val resultado = linkedMapOf<String, ByteArray>()

        config.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEachIndexed { posicao, item ->
                val partes = item.split(':', limit = 2).map { it.trim() }
                val nome = partes.getOrNull(0).orEmpty()
                val chave = partes.getOrNull(1).orEmpty()

                // Só a posição no log: uma entrada sem ":" pode ser a própria chave colada sem o nome
                if (nome.isEmpty() || chave.isEmpty()) {
                    log.error("Entrada {} das chaves de dispositivo ignorada: formato esperado nome:chave", posicao + 1)
                    return@forEachIndexed
                }

                if (CHAVE_DE_EXEMPLO.containsMatchIn(chave)) {
                    log.error("Chave do dispositivo {} IGNORADA: é a do arquivo de exemplo (pública). Gere uma chave aleatória de 32 caracteres ou mais", nome)
                    return@forEachIndexed
                }
                if (chave.length < TAMANHO_MINIMO_CHAVE) {
                    log.error("Chave do dispositivo {} IGNORADA: tem menos de {} caracteres (fácil de adivinhar). Use 32 ou mais", nome, TAMANHO_MINIMO_CHAVE)
                    return@forEachIndexed
                }

                if (resultado.put(nome, chave.toByteArray()) != null) {
                    log.warn("Dispositivo {} aparece mais de uma vez nas chaves: vale a última", nome)
                }
            }

        return resultado
    }
}
