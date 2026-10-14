package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.security.OperadorNoColetor
import org.example.service.SessaoInvalidaException
import org.example.service.SessaoOperadorService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Identifica o operador logado no coletor (depois do ChaveDispositivoFilter, que já identificou o coletor).
 *
 * Headers:
 *  - X-Sessao-Operador: token da sessão (POST /api/android/login);
 *  - X-Momento: quando a ação aconteceu no coletor (ISO-8601 com fuso). Ações feitas offline chegam depois,
 *    e valem se a sessão era válida NAQUELE momento. Sem o header, vale a hora de agora.
 *
 * Mantém o principal com o nome do dispositivo (OperadorNoColetor) e junta as permissões do operador
 * às do coletor, para os @PreAuthorize("@authService.temPermissao(...)") funcionarem.
 *
 * Login e ping não exigem operador. NÃO é @Component: é usado só na cadeia de /api/android (SecurityConfig).
 */
class OperadorFilter(
    private val sessoes: SessaoOperadorService,
    private val corte: CorteLoginOperador,
    private val relogio: Clock
) : OncePerRequestFilter() {

    companion object {
        const val HEADER_SESSAO = "X-Sessao-Operador"
        const val HEADER_MOMENTO = "X-Momento"
        private val SEM_OPERADOR = setOf("/api/android/login", "/api/android/ping", "/api/android/esqueci-senha")
    }

    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest) =
        request.requestURI.removePrefix(request.contextPath) in SEM_OPERADOR

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val atual = SecurityContextHolder.getContext().authentication
        if (atual == null) {
            chain.doFilter(request, response) // sem coletor identificado: o entry point responde 401
            return
        }

        val dispositivo = atual.name
        val momento = momentoDaAcao(request, dispositivo)
        val token = request.getHeader(HEADER_SESSAO)?.trim().orEmpty()

        if (token.isEmpty()) {
            if (filaAntigaAceitavel(request, momento)) {
                // Leitura guardada no coletor desde antes do login de operador existir: aceita sem operador
                log.warn("Ação de {} sem operador aceita por ser anterior ao login de operador ({} {}, feita em {}; aceito até {})",
                    dispositivo, request.method, request.requestURI, momento, corte.momento.plus(sessoes.janelaSincronizacao))
                autenticar(atual.authorities, OperadorNoColetor(dispositivo, null), listOf(SimpleGrantedAuthority("LEITURA_READ")))
                chain.doFilter(request, response)
                return
            }

            log.warn("Requisição do coletor {} sem operador logado: {} {}", dispositivo, request.method, request.requestURI)
            recusar(response, "Operador não identificado: faça login no coletor")
            return
        }

        val operador = try {
            sessoes.validar(token, dispositivo, momento)
        } catch (e: SessaoInvalidaException) {
            log.warn("Sessão de operador recusada no coletor {} ({} {}, ação em {}): {}",
                dispositivo, request.method, request.requestURI, momento, e.message)
            recusar(response, e.message ?: "Sessão do operador inválida")
            return
        }

        autenticar(atual.authorities, OperadorNoColetor(dispositivo, operador), operador.authorities)
        log.debug("Operador {} no coletor {}: {} {}", operador.email, dispositivo, request.method, request.requestURI)

        chain.doFilter(request, response)
    }

    private fun autenticar(doColetor: Collection<GrantedAuthority>, principal: OperadorNoColetor, doOperador: Collection<GrantedAuthority>) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, null, doColetor + doOperador)
    }

    /**
     * Fila antiga (de antes do login de operador): aceita sem operador SÓ se
     *  - a ação aconteceu antes da instalação da V4;
     *  - o servidor ainda está dentro do prazo de sincronização depois dessa instalação (relógio do servidor:
     *    o X-Momento vem do coletor e não pode, sozinho, abrir a porta para sempre);
     *  - é um endpoint da fila antiga: enviar leitura, enviar não cadastrada ou consultar a tag para classificá-la.
     *    Inventário, cadastro e o resto sempre exigem operador.
     */
    private fun filaAntigaAceitavel(request: HttpServletRequest, momento: LocalDateTime): Boolean {
        val caminho = request.requestURI.removePrefix(request.contextPath)
        val endpointDaFilaAntiga = when (request.method) {
            "POST" -> caminho == "/api/android/leitura" || caminho == "/api/android/naocadastrada"
            "GET" -> caminho.startsWith("/api/android/tags/") && !caminho.startsWith("/api/android/tags/patrimonio/")
            else -> false
        }

        return endpointDaFilaAntiga &&
            momento.isBefore(corte.momento) &&
            !LocalDateTime.now(relogio).isAfter(corte.momento.plus(sessoes.janelaSincronizacao))
    }

    private fun momentoDaAcao(request: HttpServletRequest, dispositivo: String): LocalDateTime {
        val agora = LocalDateTime.now(relogio)
        val texto = request.getHeader(HEADER_MOMENTO)?.trim()?.takeIf { it.isNotEmpty() } ?: return agora

        val momento = try {
            OffsetDateTime.parse(texto).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        } catch (e: DateTimeParseException) {
            log.warn("Header {} inválido vindo do coletor {}: '{}'. Usando a hora de agora", HEADER_MOMENTO, dispositivo, texto)
            return agora
        }

        // Relógio do coletor adiantado: não deixa uma ação "do futuro" usar a sessão além do horário real
        if (momento.isAfter(agora.plus(SessaoOperadorService.TOLERANCIA))) {
            log.warn("Header {} no futuro vindo do coletor {} ({}): usando a hora de agora", HEADER_MOMENTO, dispositivo, texto)
            return agora
        }
        return momento
    }

    private fun recusar(response: HttpServletResponse, mensagem: String) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"status":401,"codigo":"SESSAO_OPERADOR","mensagem":"${mensagem.replace("\"", "'")}"}""")
    }
}
