package org.example.service

import org.example.model.UsuarioEntity
import org.example.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * "Esqueceu a senha?" sem email: o pedido fica registrado no usuário e aparece para o ADMIN, que gera uma
 * senha temporária e a entrega à pessoa. Com ela, a pessoa entra e é obrigada a criar uma senha nova.
 *
 * O formulário é público, então:
 * - a resposta é sempre a mesma, exista ou não o email (não revela quem tem conta);
 * - cada pessoa tem no máximo um pedido em aberto (pedir de novo não cria outro);
 * - cada IP pode pedir no máximo [MAX_PEDIDOS_POR_IP] vezes por [JANELA_IP].
 */
@Service
class RecuperacaoSenhaService(
    private val repo: UserRepository,
    private val encoder: PasswordEncoder,
    private val politicaSenha: PoliticaSenha,
    private val tentativasLogin: TentativasLoginService,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val aleatorio = SecureRandom()
    private val pedidosPorIp = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    /** Registra o pedido (se o email existir). Nunca lança erro por email desconhecido: a tela responde igual. */
    @Transactional
    fun pedir(email: String, ip: String?) {
        // Sem quebras de linha: o texto vem de fora e vai para o log
        val emailLimpo = email.replace(Regex("[\r\n\t]"), " ").trim().take(255)
        if (emailLimpo.isEmpty()) return

        if (!dentroDoLimite(ip ?: "desconhecido")) {
            log.warn("Pedido de nova senha ignorado: IP {} passou de {} pedidos em {} min (email informado: '{}')",
                ip, MAX_PEDIDOS_POR_IP, JANELA_IP.toMinutes(), emailLimpo)
            return
        }

        val usuario = repo.findByEmailIgnoreCase(emailLimpo)
        if (usuario == null) {
            log.info("Pedido de nova senha para um email não cadastrado: '{}' (IP {})", emailLimpo, ip)
            return
        }

        if (usuario.senhaPedidaEm != null) {
            log.info("Pedido de nova senha repetido por {} (IP {}): já havia um pedido em aberto desde {}",
                usuario.email, ip, usuario.senhaPedidaEm)
            return
        }

        usuario.senhaPedidaEm = LocalDateTime.now(relogio)
        repo.save(usuario)
        log.warn("Pedido de nova senha: {} esqueceu a senha (IP {}). O administrador precisa gerar uma senha temporária em Usuários",
            usuario.email, ip)
    }

    fun pendentes(): List<UsuarioEntity> = repo.findBySenhaPedidaEmIsNotNullOrderBySenhaPedidaEm()

    fun quantidadePendentes(): Long = repo.countBySenhaPedidaEmIsNotNull()

    /**
     * Gera uma senha temporária para o usuário, fecha o pedido em aberto e libera o login se estava bloqueado.
     * Devolve a senha em texto UMA vez, para o ADMIN repassar; no banco fica só o hash.
     */
    @Transactional
    fun gerarSenhaTemporaria(usuarioId: Long): SenhaTemporaria {
        val usuario = buscar(usuarioId)
        val senha = gerarSenhaValida(usuario)

        val tinhaPedido = usuario.senhaPedidaEm != null
        usuario.senha = encoder.encode(senha)
        usuario.trocarSenha = true
        usuario.senhaPedidaEm = null
        repo.save(usuario)

        val desbloqueado = tentativasLogin.desbloquear(usuario.email)

        log.warn("Senha temporária gerada para {} por {}{}{}. No próximo login a pessoa precisa criar uma senha nova",
            usuario.email, quemFez(),
            if (tinhaPedido) " (atendendo ao pedido de nova senha)" else "",
            if (desbloqueado) "; bloqueio de login por tentativas erradas removido" else "")

        return SenhaTemporaria(usuario.nome, usuario.email, senha)
    }

    /** Descarta o pedido sem mudar a senha (ex.: a pessoa lembrou a senha ou o pedido não foi ela quem fez). */
    @Transactional
    fun descartarPedido(usuarioId: Long) {
        val usuario = buscar(usuarioId)
        if (usuario.senhaPedidaEm == null) return

        usuario.senhaPedidaEm = null
        repo.save(usuario)
        log.info("Pedido de nova senha de {} descartado por {} (a senha não foi alterada)", usuario.email, quemFez())
    }

    // Letras e números sem os que se confundem ao ditar ou ler (0/O, 1/l/I)
    private fun gerarSenhaValida(usuario: UsuarioEntity): String {
        repeat(50) {
            val senha = (1..TAMANHO_SENHA).map { ALFABETO[aleatorio.nextInt(ALFABETO.length)] }.joinToString("")
            if (politicaSenha.problemas(senha, usuario.email, usuario.nome).isEmpty()) return senha
        }
        throw IllegalStateException("Não foi possível gerar uma senha temporária que atenda às regras de senha")
    }

    private fun dentroDoLimite(ip: String): Boolean {
        val agora = relogio.instant()
        if (pedidosPorIp.size > LIMITE_IPS) pedidosPorIp.clear() // nunca cresce sem limite
        val pedidos = pedidosPorIp.computeIfAbsent(ip) { ArrayDeque() }
        synchronized(pedidos) {
            while (pedidos.isNotEmpty() && Duration.between(pedidos.first(), agora) >= JANELA_IP) pedidos.removeFirst()
            if (pedidos.size >= MAX_PEDIDOS_POR_IP) return false
            pedidos.addLast(agora)
            return true
        }
    }

    private fun buscar(usuarioId: Long): UsuarioEntity =
        repo.findById(usuarioId).orElseThrow { IllegalArgumentException("Usuário não encontrado") }

    private fun quemFez(): String = SecurityContextHolder.getContext().authentication?.name ?: "sistema"

    data class SenhaTemporaria(val nome: String, val email: String, val senha: String)

    companion object {
        const val MAX_PEDIDOS_POR_IP = 5
        val JANELA_IP: Duration = Duration.ofMinutes(15)
        private const val LIMITE_IPS = 10_000
        private const val TAMANHO_SENHA = 10
        private const val ALFABETO = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
