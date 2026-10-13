package org.example.service

import org.example.model.SessaoOperadorEntity
import org.example.repository.SessaoOperadorRepository
import org.example.repository.UserRepository
import org.example.security.UserPrincipal
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64

data class SessaoCriada(
    val token: String,
    val expiraEm: OffsetDateTime,
    val nome: String,
    val email: String,
    val podeLer: Boolean,
    /** Operador com RFID/CREATE E coletor liberado em rfid.dispositivos.cadastro. */
    val podeCadastrar: Boolean,
    /** A senha não atende mais à política de senha: o app sugere trocar na web. */
    val senhaFraca: Boolean
)

class LoginRecusadoException(val status: Int, mensagem: String, val retryAfterSegundos: Long? = null) : RuntimeException(mensagem)

/** Motivo de uma sessão não valer para uma ação; vira 401 em JSON no OperadorFilter. */
class SessaoInvalidaException(mensagem: String) : RuntimeException(mensagem)

/**
 * Login do operador no coletor Android e validação das sessões (12h por padrão).
 *
 * Segurança (mesma semântica do login da web):
 *  - o bloqueio por tentativas (TentativasLoginService, compartilhado com a web) é conferido ANTES da senha;
 *  - email inexistente e senha errada dão a MESMA resposta e contam como falha;
 *  - o token é aleatório (SecureRandom) e só o hash SHA-256 vai para o banco; token e senha nunca vão para o log;
 *  - a sessão vale só no coletor em que foi criada; as permissões do usuário são recarregadas a cada uso.
 */
@Service
class SessaoOperadorService(
    private val sessoes: SessaoOperadorRepository,
    private val usuarios: UserRepository,
    private val detalhes: CustomUserDetailsService,
    private val senhas: PasswordEncoder,
    private val tentativas: TentativasLoginService,
    private val politicaSenha: PoliticaSenha,
    private val cadastroColetor: CadastroColetorService,
    @Value("\${rfid.operador.duracao-sessao:12h}") private val duracao: Duration,
    /**
     * Quanto tempo (relógio do SERVIDOR) uma ação feita offline ainda pode chegar depois de acontecer,
     * e depois do fim da sessão. Passado esse prazo o token morre de vez, qualquer que seja o X-Momento:
     * sem isso, um token vazado serviria para sempre mandando um X-Momento antigo.
     */
    @Value("\${rfid.operador.janela-sincronizacao:72h}") val janelaSincronizacao: Duration,
    private val relogio: Clock
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val aleatorio = SecureRandom()

    @Transactional
    fun entrar(emailInformado: String, senha: String, dispositivo: String, ip: String?): SessaoCriada {
        val email = emailInformado.trim()

        tentativas.tempoRestanteDeBloqueio(email, ip)?.let { restante ->
            val minutos = (restante.toSeconds() + 59) / 60
            log.warn("Login no coletor {} recusado sem conferir a senha: '{}' (IP {}) está bloqueado por mais {} min", dispositivo, email.lowercase(), ip, minutos)
            throw LoginRecusadoException(429, "Muitas tentativas sem sucesso. Aguarde $minutos minuto(s) e tente de novo.", restante.toSeconds().coerceAtLeast(1))
        }

        val usuario = usuarios.findByEmail(email)
        if (usuario == null || !senhas.matches(senha, usuario.senha)) {
            tentativas.registrarFalha(email, ip)
            log.warn("Login no coletor {} recusado: email ou senha incorretos para '{}' (IP {})", dispositivo, email.lowercase(), ip)
            throw LoginRecusadoException(401, MENSAGEM_CREDENCIAIS)
        }

        val principal = detalhes.loadUserByUsername(usuario.email) as UserPrincipal
        val podeLer = temPermissao(principal, "LEITURA_READ")

        if (!podeLer) {
            log.warn("Login no coletor {} recusado: '{}' não tem permissão de Leituras (IP {})", dispositivo, usuario.email, ip)
            throw LoginRecusadoException(403, "Seu usuário não tem permissão para usar o coletor (é preciso acesso a Leituras). Fale com o administrador.")
        }

        tentativas.registrarSucesso(email)

        val agora = LocalDateTime.now(relogio)
        val anteriores = sessoes.encerrarAbertas(dispositivo, agora)
        if (anteriores > 0) log.info("Login no coletor {}: {} sessão(ões) anterior(es) do coletor encerrada(s)", dispositivo, anteriores)

        val token = gerarToken()
        val expiraEm = agora.plus(duracao)
        sessoes.save(SessaoOperadorEntity(hash(token), principal.id, dispositivo, agora, expiraEm))

        val podeCadastrar = temPermissao(principal, "RFID_CREATE") && cadastroColetor.podeCadastrar(dispositivo)
        val senhaFraca = politicaSenha.problemas(senha, usuario.email, usuario.nome).isNotEmpty()

        log.info(
            "Operador {} ({}) entrou no coletor {} até {} (IP {}; pode cadastrar: {})",
            principal.nome, principal.email, dispositivo, expiraEm, ip, if (podeCadastrar) "sim" else "não"
        )
        if (senhaFraca) log.info("Operador {} usa senha fora da política de senha: o app vai sugerir a troca", principal.email)

        return SessaoCriada(
            token = token,
            expiraEm = expiraEm.atZone(ZoneId.systemDefault()).toOffsetDateTime(),
            nome = principal.nome,
            email = principal.email,
            podeLer = true,
            podeCadastrar = podeCadastrar,
            senhaFraca = senhaFraca
        )
    }

    @Transactional
    fun sair(token: String, dispositivo: String) {
        val sessao = sessoes.findById(hash(token)).orElse(null)

        if (sessao == null || sessao.dispositivo != dispositivo) {
            log.warn("Logout no coletor {} com sessão desconhecida: ignorado", dispositivo)
            return
        }
        if (sessao.encerradaEm == null) {
            sessao.encerradaEm = LocalDateTime.now(relogio)
            sessoes.save(sessao)
        }
        val email = usuarios.findById(sessao.usuarioId).map { it.email }.orElse("?")
        log.info("Operador {} saiu do coletor {}", email, dispositivo)
    }

    /**
     * Confere se a sessão do [token] autoriza uma ação feita no [momento] (hora do coletor, no fuso do
     * servidor) neste [dispositivo]. Devolve o usuário com as permissões ATUAIS.
     */
    @Transactional(readOnly = true)
    fun validar(token: String, dispositivo: String, momento: LocalDateTime): UserPrincipal {
        val sessao = sessoes.findById(hash(token)).orElse(null)
            ?: throw SessaoInvalidaException("Sessão do operador desconhecida: faça login de novo")

        if (sessao.dispositivo != dispositivo) {
            log.warn("Sessão do coletor {} usada no coletor {}: recusada", sessao.dispositivo, dispositivo)
            throw SessaoInvalidaException("Esta sessão é de outro coletor: faça login neste coletor")
        }

        // Expiração: tolera a diferença de relógio entre coletor e servidor. Logout (encerradaEm, relógio
        // do servidor): vale na hora, sem tolerância, para o token não continuar servindo depois do "Sair".
        val encerrada = sessao.encerradaEm?.takeIf { it.isBefore(sessao.expiraEm) }
        val limite = encerrada ?: sessao.expiraEm.plus(TOLERANCIA)

        val motivo = if (encerrada != null) "encerrada" else "expirada"

        if (momento.isBefore(sessao.criadaEm.minus(TOLERANCIA)) || momento.isAfter(limite)) {
            throw SessaoInvalidaException("Sessão do operador $motivo: faça login de novo")
        }

        // O X-Momento vem do coletor: sozinho, permitiria usar um token velho para sempre. Pelo relógio do
        // SERVIDOR, a sessão morre de vez [janelaSincronizacao] depois do fim, e nenhuma ação mais antiga
        // que essa janela é aceita (é o tempo máximo que um coletor pode ficar offline sem perder leituras).
        val agora = LocalDateTime.now(relogio)
        if (agora.isAfter(limite.plus(janelaSincronizacao))) {
            log.warn("Token de sessão {} há mais de {} h usado no coletor {}: recusado", motivo, janelaSincronizacao.toHours(), dispositivo)
            throw SessaoInvalidaException("Sessão do operador $motivo: faça login de novo")
        }
        if (momento.isBefore(agora.minus(janelaSincronizacao))) {
            log.warn("Ação de {} no coletor {} chegou {} h depois de feita, além do prazo de sincronização: recusada",
                momento, dispositivo, Duration.between(momento, agora).toHours())
            throw SessaoInvalidaException("Ação antiga demais: chegou ao servidor depois do prazo de ${janelaSincronizacao.toHours()} h")
        }

        val usuario = usuarios.findById(sessao.usuarioId).orElse(null)
            ?: throw SessaoInvalidaException("O usuário desta sessão não existe mais")

        return try {
            detalhes.loadUserByUsername(usuario.email) as UserPrincipal
        } catch (e: UsernameNotFoundException) {
            throw SessaoInvalidaException("O usuário desta sessão não existe mais")
        }
    }

    private fun temPermissao(usuario: UserPrincipal, permissao: String) =
        usuario.authorities.any { it.authority == "ROLE_ADMIN" || it.authority == permissao }

    private fun gerarToken(): String {
        val bytes = ByteArray(32)
        aleatorio.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val MENSAGEM_CREDENCIAIS = "Email ou senha incorretos"

        /** Diferença aceita entre o relógio do coletor e o do servidor. */
        val TOLERANCIA: Duration = Duration.ofMinutes(5)

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
