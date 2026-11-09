package org.example.service

import org.example.repository.SessaoOperadorRepository
import org.example.security.UserPrincipal
import org.slf4j.LoggerFactory
import org.springframework.security.core.session.SessionRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** Quantas sessões foram encerradas (web e coletores). */
data class SessoesEncerradas(val web: Int, val coletores: Int) {
    val total get() = web + coletores
}

/**
 * Derruba as sessões abertas de um usuário: as da web (pelo SessionRegistry) e as dos coletores (o token do
 * operador deixa de valer). Usado quando o ADMIN desativa o usuário ou gera senha temporária, e quando a pessoa
 * troca a própria senha (aí a sessão em que ela está continua).
 */
@Service
class SessoesUsuarioService(
    private val sessoesWeb: SessionRegistry,
    private val sessoesColetor: SessaoOperadorRepository,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** [manterSessaoWeb]: id da sessão web que continua (a de quem trocou a própria senha). */
    @Transactional
    fun encerrar(usuarioId: Long, motivo: String, manterSessaoWeb: String? = null): SessoesEncerradas {
        // Compara pelo id: o principal guardado na sessão pode ser de antes de uma mudança de permissões
        val web = sessoesWeb.allPrincipals
            .filter { (it as? UserPrincipal)?.id == usuarioId }
            .flatMap { sessoesWeb.getAllSessions(it, false) }
            .filter { it.sessionId != manterSessaoWeb }
            .onEach { it.expireNow() }
            .size

        // Encerrada pelo servidor (desativação, senha): sem a margem de relógio do "Sair" (SessaoOperadorService)
        val coletores = sessoesColetor.encerrarAbertasDoUsuario(usuarioId, LocalDateTime.now(relogio), org.example.model.FimSessao.ADMIN.name)

        if (web + coletores > 0) {
            log.info("Sessões do usuário {} encerradas ({}): {} na web, {} em coletores", usuarioId, motivo, web, coletores)
        }
        return SessoesEncerradas(web, coletores)
    }

    /**
     * Só as sessões web do usuário (o perfil e as permissões ficam guardados na sessão desde o login). As do
     * coletor não precisam: lá as permissões são recarregadas a cada ação. Devolve quantas foram encerradas.
     */
    fun encerrarWeb(usuarioId: Long, motivo: String): Int {
        val web = sessoesWeb.allPrincipals
            .filter { (it as? UserPrincipal)?.id == usuarioId }
            .flatMap { sessoesWeb.getAllSessions(it, false) }
            .onEach { it.expireNow() }
            .size
        if (web > 0) log.info("Sessões web do usuário {} encerradas ({}): {}", usuarioId, motivo, web)
        return web
    }
}
