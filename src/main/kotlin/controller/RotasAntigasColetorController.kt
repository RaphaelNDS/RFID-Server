package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Rotas antigas dos coletores (tudo em /api/tags e /api/rfid), removidas: o app usa só /api/android,
 * com chave do coletor e login de operador.
 *
 * Em vez de sumirem (o que viraria um redirecionamento silencioso para a tela de login), respondem
 * 410 Gone explicando o que fazer, e registram no log quem ainda chama — para achar coletor com app antigo.
 * Não fazem nada além disso: por isso podem ficar liberadas sem login (SecurityConfig).
 */
@RestController
class RotasAntigasColetorController(private val relogio: Clock) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Um aviso por IP+rota a cada hora: um app antigo tentando a cada poucos segundos não lota o log
    private val ultimoAviso = ConcurrentHashMap<String, Instant>()

    @RequestMapping("/api/tags/**", "/api/rfid/**")
    fun rotaRemovida(request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        avisar(request)

        return ResponseEntity.status(HttpStatus.GONE).body(
            mapOf(
                "status" to HttpStatus.GONE.value(),
                "mensagem" to MENSAGEM,
                "codigo" to null,
                "caminho" to request.requestURI
            )
        )
    }

    private fun avisar(request: HttpServletRequest) {
        val agora = relogio.instant()
        val chave = "${request.remoteAddr} ${request.method} ${request.requestURI}"
        val anterior = ultimoAviso[chave]

        if (anterior != null && Duration.between(anterior, agora) < INTERVALO_AVISO) return
        ultimoAviso[chave] = agora

        if (ultimoAviso.size > LIMITE_REGISTROS) ultimoAviso.clear() // nunca cresce sem limite

        val usuario = SecurityContextHolder.getContext().authentication
            ?.takeUnless { it is AnonymousAuthenticationToken }?.name ?: "anônimo"
        log.warn(
            "Rota antiga dos coletores chamada: {} {} (IP {}, usuário {}). Essa rota foi removida: atualize o app do coletor para a versão que usa /api/android",
            request.method, request.requestURI, request.remoteAddr, usuario
        )
    }

    companion object {
        const val MENSAGEM = "Esta rota foi removida. Atualize o app do coletor: a versão atual usa /api/android com chave do coletor e login de operador."
        private val INTERVALO_AVISO: Duration = Duration.ofHours(1)
        private const val LIMITE_REGISTROS = 10_000
    }
}
