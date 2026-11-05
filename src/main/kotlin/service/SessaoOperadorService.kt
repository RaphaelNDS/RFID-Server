package org.example.service

import org.example.model.Role
import org.example.model.SessaoOperadorEntity
import org.example.model.UsuarioEntity
import org.example.repository.SessaoOperadorRepository
import org.example.repository.UserRepository
import org.example.security.UserPrincipal
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
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

        val usuario = conferirCredenciais(email, senha, dispositivo, ip, "Login")

        // Senha temporária gerada pelo ADMIN ("Esqueceu a senha?"): como na web, só serve para criar a senha nova.
        // O coletor não tem tela de troca de senha, então a pessoa troca na web e depois entra aqui.
        if (usuario.trocarSenha) {
            log.warn("Login no coletor {} recusado: '{}' entrou com senha temporária e ainda não criou a senha nova (IP {})", dispositivo, usuario.email, ip)
            throw LoginRecusadoException(403, MENSAGEM_SENHA_TEMPORARIA)
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

    /**
     * "Esqueci o PIN" das Configurações do coletor: um ADMIN confirma com email e senha e o coletor apaga o PIN.
     * Não cria sessão: um login aqui encerraria a sessão do operador que está usando o coletor (e as leituras
     * guardadas com ela seriam recusadas). Mesmas proteções do login: bloqueio por tentativas, senha errada conta.
     */
    @Transactional
    fun autorizarLiberacaoPin(emailInformado: String, senha: String, dispositivo: String, ip: String?) {
        val email = emailInformado.trim()
        val usuario = conferirCredenciais(email, senha, dispositivo, ip, "Pedido de liberação do PIN")

        if (usuario.role != Role.ADMIN) {
            log.warn("Liberação do PIN no coletor {} recusada: '{}' não é administrador (IP {})", dispositivo, usuario.email, ip)
            throw LoginRecusadoException(403, MENSAGEM_SO_ADMIN_PIN)
        }

        tentativas.registrarSucesso(email)
        log.warn("PIN das Configurações do coletor {} liberado pelo administrador {} (IP {})", dispositivo, usuario.email, ip)
    }

    /** Bloqueio por tentativas, email e senha e usuário ativo: o mesmo para o login e para liberar o PIN. */
    private fun conferirCredenciais(email: String, senha: String, dispositivo: String, ip: String?, acao: String): UsuarioEntity {
        // Bloqueio por origem = coletor + IP, e não só o IP: vários coletores costumam sair para o servidor pelo
        // mesmo IP (NAT do roteador), e um operador errando a senha travaria o login de todos. Para chegar aqui o
        // coletor já apresentou a chave dele, então um atacante não troca de "coletor" à vontade. O bloqueio por
        // email continua o mesmo da web (5 erros naquela conta, venham de onde vierem).
        val origem = origemDoLogin(dispositivo, ip)

        tentativas.tempoRestanteDeBloqueio(email, origem)?.let { restante ->
            val minutos = (restante.toSeconds() + 59) / 60
            log.warn("{} no coletor {} recusado sem conferir a senha: '{}' ou o coletor (IP {}) está bloqueado por mais {} min", acao,
                dispositivo, email.lowercase(), ip, minutos)
            throw LoginRecusadoException(429, "Muitas tentativas sem sucesso. Aguarde $minutos minuto(s) e tente de novo.", restante.toSeconds().coerceAtLeast(1))
        }

        val usuario = usuarios.findByEmail(email)
        if (usuario == null || !senhas.matches(senha, usuario.senha)) {
            tentativas.registrarFalha(email, origem)
            log.warn("{} no coletor {} recusado: email ou senha incorretos para '{}' (IP {})", acao, dispositivo, email.lowercase(), ip)
            throw LoginRecusadoException(401, MENSAGEM_CREDENCIAIS)
        }

        // Conferido depois da senha, como na web: quem não sabe a senha não descobre que o usuário existe
        if (!usuario.ativo) {
            log.warn("{} no coletor {} recusado: o usuário '{}' está desativado (IP {})", acao, dispositivo, usuario.email, ip)
            throw LoginRecusadoException(403, MENSAGEM_DESATIVADO)
        }
        return usuario
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

        // Depois do "Sair" vale só o que aconteceu ANTES: uma ação no mesmo instante do logout é recusada (no
        // Windows o relógio anda em passos de até ~15 ms, e a ação logo após o "Sair" pode ganhar a mesma hora)
        val depoisDoFim = if (encerrada != null) !momento.isBefore(limite) else momento.isAfter(limite)

        if (momento.isBefore(sessao.criadaEm.minus(TOLERANCIA)) || depoisDoFim) {
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

        val principal = try {
            detalhes.loadUserByUsername(usuario.email) as UserPrincipal
        } catch (e: UsernameNotFoundException) {
            throw SessaoInvalidaException("O usuário desta sessão não existe mais")
        }

        // Desativado depois do login: o token deixa de valer já na próxima ação (o ADMIN também encerra as sessões)
        if (!principal.isEnabled) {
            log.warn("Ação no coletor {} recusada: o usuário {} foi desativado", dispositivo, principal.email)
            throw SessaoInvalidaException(MENSAGEM_DESATIVADO)
        }
        return principal
    }

    /**
     * Limpeza: apaga as sessões que já não autorizam nada há mais de [GUARDAR_SESSOES_MORTAS]. Uma sessão morre de
     * vez [janelaSincronizacao] depois do fim (logout ou expiração, o que vier antes); a conta usa a expiração, que
     * é sempre o fim mais tardio, então nunca apaga sessão que ainda vale. Roda 10 min depois de o servidor subir
     * e depois a cada 24 h (horário fixo de madrugada não roda com o computador desligado).
     */
    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT24H")
    @Transactional
    fun apagarSessoesMortas(): Int {
        val limite = LocalDateTime.now(relogio).minus(janelaSincronizacao).minus(TOLERANCIA).minus(GUARDAR_SESSOES_MORTAS)
        return runCatching { sessoes.apagarExpiradasAntesDe(limite) }
            .onSuccess { if (it > 0) log.info("Limpeza: {} sessão(ões) de operador expiradas antes de {} apagada(s)", it, limite.toLocalDate()) }
            .onFailure { log.error("Limpeza das sessões de operador falhou: tenta de novo em 24 h", it) }
            .getOrDefault(0)
    }

    /** Origem das tentativas de login do coletor no TentativasLoginService: "coletor-01@10.0.0.5". */
    private fun origemDoLogin(dispositivo: String, ip: String?) = "$dispositivo@${ip ?: "desconhecido"}"

    private fun temPermissao(usuario: UserPrincipal, permissao: String) =
        usuario.authorities.any { it.authority == "ROLE_ADMIN" || it.authority == permissao }

    private fun gerarToken(): String {
        val bytes = ByteArray(32)
        aleatorio.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val MENSAGEM_CREDENCIAIS = "Email ou senha incorretos"
        const val MENSAGEM_SENHA_TEMPORARIA =
            "Você está com uma senha temporária: entre pela web, crie sua senha nova e depois entre no coletor com ela."
        const val MENSAGEM_DESATIVADO = "Seu usuário foi desativado. Fale com o administrador."
        const val MENSAGEM_SO_ADMIN_PIN = "Só um administrador pode liberar o PIN das Configurações do coletor."

        /** Diferença aceita entre o relógio do coletor e o do servidor. */
        val TOLERANCIA: Duration = Duration.ofMinutes(5)

        /** Quanto tempo a sessão morta continua no banco antes da limpeza (para conferir algum problema recente). */
        val GUARDAR_SESSOES_MORTAS: Duration = Duration.ofDays(30)

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
