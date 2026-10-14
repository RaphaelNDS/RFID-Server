package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent
import org.springframework.security.authentication.event.AuthenticationSuccessEvent
import org.springframework.security.web.authentication.WebAuthenticationDetails
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Proteção contra adivinhação de senha no login.
 *
 * - Por email: [maxTentativas] erros dentro da janela bloqueiam aquele login.
 * - Por origem: [maxTentativasIp] erros (em qualquer conta) bloqueiam a origem — pega quem testa a mesma senha em
 *   várias contas. Na web a origem é o IP; no coletor é "coletor@IP" (SessaoOperadorService), para vários coletores
 *   atrás do mesmo IP (NAT) não travarem uns aos outros por causa de um operador errando a senha.
 *
 * Emails inexistentes contam igual aos existentes, para não revelar quais contas existem.
 * Os contadores ficam em memória: reiniciar o servidor zera tudo (aceitável para um servidor único).
 */
@Service
class TentativasLoginService(
    @Value("\${rfid.login.max-tentativas:5}") private val maxTentativas: Int,
    @Value("\${rfid.login.max-tentativas-ip:20}") private val maxTentativasIp: Int,
    @Value("\${rfid.login.bloqueio-minutos:15}") bloqueioMinutos: Long,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Tempo de bloqueio e também a janela em que os erros são contados. */
    private val duracao: Duration = Duration.ofMinutes(bloqueioMinutos)

    private class Registro {
        val falhas = ArrayDeque<Instant>()
        var bloqueadoAte: Instant? = null
    }

    private val porEmail = ConcurrentHashMap<String, Registro>()
    private val porIp = ConcurrentHashMap<String, Registro>()

    /** Quanto falta de bloqueio para esse email ou origem; null se o login está liberado. */
    fun tempoRestanteDeBloqueio(email: String?, origem: String?): Duration? {
        val agora = relogio.instant()
        return listOfNotNull(
            email?.let { porEmail[normalizar(it)] },
            origem?.let { porIp[it] }
        ).mapNotNull { registro ->
            synchronized(registro) { registro.bloqueadoAte?.takeIf { it.isAfter(agora) } }
        }.maxOrNull()?.let { Duration.between(agora, it) }
    }

    fun registrarFalha(email: String, origem: String?) {
        val chave = normalizar(email)

        contar(porEmail.computeIfAbsent(chave) { Registro() }, maxTentativas)?.let { falhas ->
            log.warn(
                "Login de '{}' bloqueado por {} min após {} tentativas com senha errada (última origem: {})",
                chave, duracao.toMinutes(), falhas, origem ?: "desconhecido"
            )
        }

        if (origem != null) {
            contar(porIp.computeIfAbsent(origem) { Registro() }, maxTentativasIp)?.let { falhas ->
                log.warn(
                    "Origem {} (IP na web, coletor@IP no coletor) bloqueada por {} min após {} tentativas de login com falha (possível ataque de senhas; última conta tentada: '{}')",
                    origem, duracao.toMinutes(), falhas, chave
                )
            }
        }
    }

    fun registrarSucesso(email: String) {
        val anterior = porEmail.remove(normalizar(email)) ?: return
        val falhas = synchronized(anterior) { anterior.falhas.size }
        if (falhas > 0) {
            log.info("Login de '{}' realizado após {} tentativa(s) com senha errada", normalizar(email), falhas)
        }
    }

    /** Zera os erros e o bloqueio desse email (ex.: o ADMIN acabou de gerar uma senha nova para a pessoa). */
    fun desbloquear(email: String): Boolean = porEmail.remove(normalizar(email)) != null

    // Integração com os eventos do Spring Security (o formulário de login dispara esses eventos)

    @EventListener
    fun aoFalhar(event: AuthenticationFailureBadCredentialsEvent) =
        registrarFalha(event.authentication.name, ip(event.authentication.details))

    @EventListener
    fun aoEntrar(event: AuthenticationSuccessEvent) = registrarSucesso(event.authentication.name)

    /** Remove registros vencidos para a memória não crescer sem limite. */
    @Scheduled(fixedDelayString = "PT10M")
    fun limparExpirados() {
        val agora = relogio.instant()
        var removidos = 0
        listOf(porEmail, porIp).forEach { mapa ->
            mapa.entries.removeIf { (_, registro) ->
                synchronized(registro) {
                    descartarAntigas(registro, agora)
                    val livre = registro.falhas.isEmpty() && registro.bloqueadoAte?.isAfter(agora) != true
                    if (livre) removidos++
                    livre
                }
            }
        }
        if (removidos > 0) log.debug("Limpeza de tentativas de login: {} registro(s) expirado(s) removido(s)", removidos)
    }

    /** Conta a falha; devolve o número de falhas se ela causou um bloqueio agora. */
    private fun contar(registro: Registro, limite: Int): Int? = synchronized(registro) {
        val agora = relogio.instant()
        descartarAntigas(registro, agora)
        registro.falhas.addLast(agora)

        if (registro.falhas.size < limite) return null

        val falhas = registro.falhas.size
        registro.bloqueadoAte = agora.plus(duracao)
        registro.falhas.clear()
        falhas
    }

    private fun descartarAntigas(registro: Registro, agora: Instant) {
        val limite = agora.minus(duracao)
        while (registro.falhas.firstOrNull()?.isBefore(limite) == true) registro.falhas.removeFirst()
    }

    private fun normalizar(email: String) = email.trim().lowercase()

    private fun ip(details: Any?) = (details as? WebAuthenticationDetails)?.remoteAddress
}
