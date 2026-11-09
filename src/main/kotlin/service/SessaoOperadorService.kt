package org.example.service

import org.example.model.AreaAuditoria
import org.example.model.FimSessao
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

/**
 * Por que a sessão não valeu, no campo "motivo" do 401 (e dos itens recusados do lote). DESCONHECIDA e
 * OUTRO_COLETOR indicam problema do lado do servidor ou do coletor (ex.: banco restaurado, servidor trocado),
 * não do operador: o app pode segurar as leituras na fila em vez de recusá-las de vez.
 */
enum class MotivoSessaoInvalida {
    /** Token que o servidor não conhece (banco restaurado ou outro servidor). */
    DESCONHECIDA,
    /** Token de outro coletor. */
    OUTRO_COLETOR,
    /** Ação depois do "Sair" (ou antes do login). */
    ENCERRADA,
    /** Ação depois do fim das 12 h da sessão, ou token velho além do prazo de sincronização. */
    EXPIRADA,
    /** A ação chegou depois do prazo de sincronização (coletor ficou offline tempo demais). */
    ANTIGA_DEMAIS,
    /** O usuário da sessão foi excluído. */
    USUARIO_INEXISTENTE,
    /** O usuário foi desativado pelo ADMIN. */
    DESATIVADO,
    /** Requisição sem o header da sessão. */
    SEM_OPERADOR
}

/** Motivo de uma sessão não valer para uma ação; vira 401 em JSON no OperadorFilter. */
class SessaoInvalidaException(mensagem: String, val motivo: MotivoSessaoInvalida) : RuntimeException(mensagem)

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
    private val relogio: Clock,
    private val auditoria: AuditoriaService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val aleatorio = SecureRandom()

    /** Hash de uma senha que ninguém sabe, só para o email inexistente levar o mesmo tempo que o existente. */
    private val hashFicticio: String by lazy { senhas.encode("ficticia-" + gerarToken()) }

    @Transactional
    fun entrar(emailInformado: String, senha: String, dispositivo: String, ip: String?): SessaoCriada {
        val email = emailInformado.trim()

        val usuario = conferirCredenciais(email, senha, dispositivo, ip, "Login")

        val principal = detalhes.loadUserByUsername(usuario.email) as UserPrincipal

        // Senha temporária gerada pelo ADMIN ("Esqueceu a senha?"): como na web, só serve para criar a senha nova.
        // O coletor não tem tela de troca de senha, então a pessoa troca na web e depois entra aqui. Vencida, a web
        // também recusa: mandar "entrar pela web" deixava a pessoa sem saída; o caminho é pedir outra ao ADMIN
        if (usuario.trocarSenha) {
            if (principal.senhaTemporariaVencida) {
                log.warn("Login no coletor {} recusado: a senha temporária de '{}' venceu sem ter sido trocada (IP {})", dispositivo, usuario.email, ip)
                throw LoginRecusadoException(403, MENSAGEM_SENHA_TEMPORARIA_VENCIDA)
            }
            log.warn("Login no coletor {} recusado: '{}' entrou com senha temporária e ainda não criou a senha nova (IP {})", dispositivo, usuario.email, ip)
            throw LoginRecusadoException(403, MENSAGEM_SENHA_TEMPORARIA)
        }

        val podeLer = temPermissao(principal, "LEITURA_READ")

        if (!podeLer) {
            log.warn("Login no coletor {} recusado: '{}' não tem permissão de Leituras (IP {})", dispositivo, usuario.email, ip)
            throw LoginRecusadoException(403, "Seu usuário não tem permissão para usar o coletor (é preciso acesso a Leituras). Fale com o administrador.")
        }

        tentativas.registrarSucesso(email)

        val agora = LocalDateTime.now(relogio)
        val anteriores = sessoes.encerrarAbertas(dispositivo, agora, FimSessao.NOVO_LOGIN.name)
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
        auditoria?.registrar(AreaAuditoria.SENHAS, "PIN do coletor liberado", dispositivo, "Pelo administrador ${usuario.email} (IP ${ip ?: "desconhecido"})")
    }

    /** Bloqueio por tentativas, email e senha e usuário ativo: o mesmo para o login e para liberar o PIN. */
    private fun conferirCredenciais(email: String, senha: String, dispositivo: String, ip: String?, acao: String): UsuarioEntity {
        // Bloqueio por origem = coletor + IP, e não só o IP: vários coletores costumam sair para o servidor pelo
        // mesmo IP (NAT do roteador), e um operador errando a senha travaria o login de todos. Para chegar aqui o
        // coletor já apresentou a chave dele, então um atacante não troca de "coletor" à vontade. O bloqueio por
        // email segue a regra da web (5 erros naquela conta e origem; 20 somando as origens).
        val origem = origemDoLogin(dispositivo, ip)

        tentativas.tempoRestanteDeBloqueio(email, origem)?.let { restante ->
            val minutos = (restante.toSeconds() + 59) / 60
            log.warn("{} no coletor {} recusado sem conferir a senha: '{}' ou o coletor (IP {}) está bloqueado por mais {} min", acao,
                dispositivo, email.lowercase(), ip, minutos)
            throw LoginRecusadoException(429, "Muitas tentativas sem sucesso. Aguarde $minutos minuto(s) e tente de novo.", restante.toSeconds().coerceAtLeast(1))
        }

        val usuario = usuarios.findByEmailIgnoreCase(email.trim())
        // Email inexistente também passa pelo BCrypt (com um hash fictício): sem isso a resposta voltava dezenas de ms
        // antes, e medindo o tempo dava para montar a lista de emails válidos. A web já faz o mesmo (Spring Security)
        val senhaConfere = senhas.matches(senha, usuario?.senha ?: hashFicticio)
        if (usuario == null || !senhaConfere) {
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
            sessao.encerradaPor = FimSessao.SAIR.name
            sessoes.save(sessao)
        }
        val email = usuarios.findById(sessao.usuarioId).map { it.email }.orElse("?")
        log.info("Operador {} saiu do coletor {}", email, dispositivo)
    }

    /**
     * Confere se a sessão do [token] autoriza uma ação feita no [momento] (hora do coletor, no fuso do
     * servidor) neste [dispositivo]. Devolve o usuário com as permissões ATUAIS. [momento] null: o coletor não
     * informou a hora (ou informou uma no futuro), e vale a hora do servidor, sem a margem de relógio do fim da sessão.
     */
    @Transactional(readOnly = true)
    fun validar(token: String, dispositivo: String, momento: LocalDateTime?): UserPrincipal {
        val sessao = sessaoDoColetor(token, dispositivo)
        val agora = LocalDateTime.now(relogio)
        // Mesma ordem de sempre: fora da sessão, depois token morto, depois ação antiga demais, depois o usuário
        val recusa = recusaDoMomento(sessao, momento, agora)
        if (recusa != null && recusa.motivo != MotivoSessaoInvalida.ANTIGA_DEMAIS) {
            throw if (recusa.motivo == MotivoSessaoInvalida.ENCERRADA && usuarioDesativado(sessao)) recusaDesativado(sessao, dispositivo) else recusa
        }
        exigirTokenVivo(sessao, dispositivo, agora)
        if (recusa != null) {
            log.warn("Ação de {} no coletor {} chegou {} h depois de feita, além do prazo de sincronização: recusada",
                momento, dispositivo, Duration.between(momento ?: agora, agora).toHours())
            throw recusa
        }
        return operadorDa(sessao, dispositivo, agora)
    }

    /**
     * Lote: confere a hora de cada leitura contra a sessão com UMA consulta da sessão e do usuário (antes o lote
     * chamava [validar] por item quando alguma leitura caía fora da sessão: até 1.500 consultas num lote de 500).
     * Devolve índice -> recusa só dos momentos fora da sessão. O que não depende do momento (sessão desconhecida,
     * de outro coletor, token morto, usuário inexistente) lança [SessaoInvalidaException] para o lote todo. Usuário
     * desativado: as leituras de antes da desativação valem e as de depois voltam recusadas com DESATIVADO.
     */
    @Transactional(readOnly = true)
    fun conferirMomentos(token: String, dispositivo: String, momentos: List<LocalDateTime?>): Map<Int, SessaoInvalidaException> {
        val sessao = sessaoDoColetor(token, dispositivo)
        val agora = LocalDateTime.now(relogio)
        exigirTokenVivo(sessao, dispositivo, agora)
        val principal = operadorDa(sessao, dispositivo, agora)
        val recusas = momentos.withIndex()
            .mapNotNull { (indice, momento) -> recusaDoMomento(sessao, momento, agora)?.let { indice to it } }
            .toMap()
        if (principal.isEnabled) return recusas
        // Desativado: o que foi feito antes da desativação vale; o que veio depois é recusado como "desativado"
        val depois = recusas.count { it.value.motivo == MotivoSessaoInvalida.ENCERRADA }
        if (depois > 0) {
            log.warn("Coletor {}: {} ação(ões) de {} feitas depois da desativação recusadas; {} de antes aceitas",
                dispositivo, depois, principal.email, momentos.size - recusas.size)
        }
        val desativado by lazy { SessaoInvalidaException(MENSAGEM_DESATIVADO, MotivoSessaoInvalida.DESATIVADO) }
        return recusas.mapValues { (_, recusa) -> if (recusa.motivo == MotivoSessaoInvalida.ENCERRADA) desativado else recusa }
    }

    /**
     * Horas informadas pelo coletor no corpo (leitura avulsa, itens de inventário) contra a sessão, com a mesma regra
     * do lote: o OperadorFilter só confere o X-Momento, e o corpo podia trazer qualquer data passada (ex.: uma leitura
     * de 2025 gravada no período antigo dos relatórios). Hora ausente ou no futuro vale como "agora".
     */
    fun conferirHorasDoColetor(token: String, dispositivo: String, horas: List<java.time.OffsetDateTime?>): Map<Int, SessaoInvalidaException> {
        if (horas.isEmpty() || token.isBlank()) return emptyMap()
        val agora = LocalDateTime.now(relogio)
        // null = sem hora do coletor (ausente ou no futuro): vale agora, sem a margem de relógio
        val momentos = horas.map { hora ->
            hora?.atZoneSameInstant(java.time.ZoneId.systemDefault())?.toLocalDateTime()?.takeUnless { it.isAfter(agora.plus(TOLERANCIA)) }
        }
        return try {
            conferirMomentos(token, dispositivo, momentos)
        } catch (e: SessaoInvalidaException) {
            horas.indices.associateWith { e }
        }
    }

    private fun sessaoDoColetor(token: String, dispositivo: String): SessaoOperadorEntity {
        val sessao = sessoes.findById(hash(token)).orElse(null)
            ?: throw SessaoInvalidaException("Sessão do operador desconhecida: faça login de novo", MotivoSessaoInvalida.DESCONHECIDA)

        if (sessao.dispositivo != dispositivo) {
            log.warn("Sessão do coletor {} usada no coletor {}: recusada", sessao.dispositivo, dispositivo)
            throw SessaoInvalidaException("Esta sessão é de outro coletor: faça login neste coletor", MotivoSessaoInvalida.OUTRO_COLETOR)
        }
        return sessao
    }

    /** Fim da sessão: o logout (sem tolerância) ou a expiração (com a tolerância de relógio). */
    private fun fimDa(sessao: SessaoOperadorEntity): Pair<LocalDateTime, Boolean> {
        // Expiração: tolera a diferença de relógio entre coletor e servidor. Logout (encerradaEm, relógio
        // do servidor): vale na hora, sem tolerância, para o token não continuar servindo depois do "Sair".
        val encerrada = sessao.encerradaEm?.takeIf { it.isBefore(sessao.expiraEm) }
        return (encerrada ?: sessao.expiraEm.plus(TOLERANCIA)) to (encerrada != null)
    }

    /**
     * Até quando (hora do coletor) uma ação ainda cabe numa sessão encerrada em [encerradaEm] (hora do servidor).
     *
     * "Sair" ou outro login no mesmo coletor: o próprio coletor encerrou, então as ações dele são todas de antes. Mas
     * elas vêm com a hora do coletor: com o relógio dele adiantado, as do último minuto chegavam com hora depois do fim
     * e eram recusadas como ENCERRADA. A margem [MARGEM_FIM_PELO_COLETOR] cobre isso. Não abre brecha nova: o X-Momento
     * vem do coletor, e quem tivesse o token já podia mandar uma hora de antes do "Sair". O que segura um token vazado
     * é o prazo pelo relógio do servidor ([exigirTokenVivo]), que não muda.
     *
     * Encerrada pelo servidor (desativação, senha) ou antes da 1.4.4 (sem motivo guardado): sem margem, como antes.
     */
    private fun limiteDoEncerramento(sessao: SessaoOperadorEntity, encerradaEm: LocalDateTime): LocalDateTime =
        when (sessao.encerradaPor) {
            FimSessao.SAIR.name, FimSessao.NOVO_LOGIN.name -> encerradaEm.plus(MARGEM_FIM_PELO_COLETOR)
            else -> encerradaEm
        }

    /**
     * A recusa para uma ação feita em [horaDoColetor] (hora do coletor), ou null se a sessão valia nesse momento.
     * [horaDoColetor] null: sem hora do coletor, vale [agora] (relógio do servidor, sem margem).
     */
    private fun recusaDoMomento(sessao: SessaoOperadorEntity, horaDoColetor: LocalDateTime?, agora: LocalDateTime): SessaoInvalidaException? {
        val (limite, encerrada) = fimDa(sessao)
        val motivo = if (encerrada) "encerrada" else "expirada"
        val momento = horaDoColetor ?: agora

        // Depois do "Sair" vale só o que aconteceu ANTES: uma ação no mesmo instante do logout é recusada (no
        // Windows o relógio anda em passos de até ~15 ms, e a ação logo após o "Sair" pode ganhar a mesma hora).
        // Com a hora do coletor, "antes" tem a margem do relógio dele (limiteDoEncerramento). Com a hora do servidor
        // não há relógio a corrigir: o token não serve para nada novo depois do "Sair", nem por alguns minutos
        val limiteDoFim = if (encerrada && horaDoColetor != null) limiteDoEncerramento(sessao, limite) else limite
        val depoisDoFim = if (encerrada) !momento.isBefore(limiteDoFim) else momento.isAfter(limite)
        if (momento.isBefore(sessao.criadaEm.minus(TOLERANCIA)) || depoisDoFim) {
            return SessaoInvalidaException("Sessão do operador $motivo: faça login de novo",
                if (encerrada) MotivoSessaoInvalida.ENCERRADA else MotivoSessaoInvalida.EXPIRADA)
        }
        // Nenhuma ação mais antiga que o prazo de sincronização (relógio do servidor) é aceita
        if (momento.isBefore(agora.minus(janelaSincronizacao))) {
            return SessaoInvalidaException("Ação antiga demais: chegou ao servidor depois do prazo de ${janelaSincronizacao.toHours()} h",
                MotivoSessaoInvalida.ANTIGA_DEMAIS)
        }
        return null
    }

    /**
     * O X-Momento vem do coletor: sozinho, permitiria usar um token velho para sempre. Pelo relógio do SERVIDOR, a
     * sessão morre de vez [janelaSincronizacao] depois do fim (o tempo máximo que um coletor fica offline).
     */
    private fun exigirTokenVivo(sessao: SessaoOperadorEntity, dispositivo: String, agora: LocalDateTime) {
        val (limite, encerrada) = fimDa(sessao)
        if (agora.isAfter(limite.plus(janelaSincronizacao))) {
            val motivo = if (encerrada) "encerrada" else "expirada"
            log.warn("Token de sessão {} há mais de {} h usado no coletor {}: recusado", motivo, janelaSincronizacao.toHours(), dispositivo)
            throw SessaoInvalidaException("Sessão do operador $motivo: faça login de novo", MotivoSessaoInvalida.EXPIRADA)
        }
    }

    /**
     * Usuário da sessão com as permissões ATUAIS. Desativado: vale a mesma regra da troca de senha, vale o que foi
     * feito enquanto a sessão valia. A desativação encerra a sessão (encerradaEm = hora da desativação), então as
     * ações de ANTES (feitas offline e enviadas depois) passam e as de depois já são recusadas pela hora (e trocadas
     * por DESATIVADO em [validar] e [conferirMomentos]). Sessão ainda aberta de usuário desativado (desativação que
     * não passou pelo ADMIN, ex.: direto no banco) não diz quando ele foi desativado: recusa tudo, como antes.
     */
    private fun operadorDa(sessao: SessaoOperadorEntity, dispositivo: String, agora: LocalDateTime): UserPrincipal {
        val usuario = usuarios.findById(sessao.usuarioId).orElse(null)
            ?: throw SessaoInvalidaException("O usuário desta sessão não existe mais", MotivoSessaoInvalida.USUARIO_INEXISTENTE)

        val principal = try {
            detalhes.loadUserByUsername(usuario.email) as UserPrincipal
        } catch (e: UsernameNotFoundException) {
            throw SessaoInvalidaException("O usuário desta sessão não existe mais", MotivoSessaoInvalida.USUARIO_INEXISTENTE)
        }

        if (!principal.isEnabled) {
            if (sessao.encerradaEm == null && sessao.expiraEm.isAfter(agora)) throw recusaDesativado(sessao, dispositivo, principal.email)
            log.info("Coletor {}: envio de {} (desativado; sessão encerrada em {}): valem só as ações feitas antes disso",
                dispositivo, principal.email, sessao.encerradaEm ?: sessao.expiraEm)
        }
        return principal
    }

    /** O usuário da sessão existe e está desativado (para a ação depois do fim da sessão dizer o motivo certo). */
    private fun usuarioDesativado(sessao: SessaoOperadorEntity): Boolean =
        usuarios.findById(sessao.usuarioId).map { !it.ativo }.orElse(false)

    private fun recusaDesativado(sessao: SessaoOperadorEntity, dispositivo: String, email: String? = null): SessaoInvalidaException {
        log.warn("Ação no coletor {} recusada: o usuário {} foi desativado (ação depois da desativação)", dispositivo,
            email ?: usuarios.findById(sessao.usuarioId).map { it.email }.orElse("?"))
        return SessaoInvalidaException(MENSAGEM_DESATIVADO, MotivoSessaoInvalida.DESATIVADO)
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
        const val MENSAGEM_SENHA_TEMPORARIA_VENCIDA =
            "Sua senha temporária venceu sem ter sido trocada. Peça uma senha nova ao administrador."
        const val MENSAGEM_DESATIVADO = "Seu usuário foi desativado. Fale com o administrador."
        const val MENSAGEM_SO_ADMIN_PIN = "Só um administrador pode liberar o PIN das Configurações do coletor."

        /** Diferença aceita entre o relógio do coletor e o do servidor. */
        val TOLERANCIA: Duration = Duration.ofMinutes(5)

        /**
         * Margem depois do "Sair" ou de outro login no mesmo coletor para as ações com a hora do coletor adiantada
         * (as do último minuto antes do fim). Igual à [TOLERANCIA]: a hora do coletor só é aceita até 5 min no
         * futuro (acima disso vira "agora"), e na 1.4.4 a margem era de 2 min. Com o relógio adiantado entre 2 e 5 min,
         * as leituras do último minuto voltavam RECUSADA/ENCERRADA, e o app não reenvia o que foi recusado: eram
         * perdidas. Não abre brecha: ver [limiteDoEncerramento].
         */
        val MARGEM_FIM_PELO_COLETOR: Duration = TOLERANCIA

        /** Quanto tempo a sessão morta continua no banco antes da limpeza (para conferir algum problema recente). */
        val GUARDAR_SESSOES_MORTAS: Duration = Duration.ofDays(30)

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
