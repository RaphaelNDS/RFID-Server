package org.example.service

import org.springframework.transaction.annotation.Transactional
import org.example.model.*
import org.example.repository.UserRepository
import org.example.request.CadastroUsuarioRequest
import org.example.request.SetupAdminRequest
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class UsuarioService(
    private val repo: UserRepository,
    private val encoder: PasswordEncoder,
    private val politicaSenha: PoliticaSenha,
    private val sessoes: SessoesUsuarioService,
    // Opcional para os testes que montam o service à mão; no Spring é sempre injetado
    private val auditoria: AuditoriaService? = null,
    // Senha atual errada em "Meu perfil" conta como erro de login (sem isso dava para testar senhas sem limite)
    private val tentativas: TentativasLoginService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun existeUsuario(): Boolean {
        return repo.count() > 0
    }

    fun listarTodos(): List<UsuarioEntity> {
        return repo.findAll()
    }

    // Sem @Transactional de propósito: cada save comita dentro do lock,
    // então uma segunda requisição concorrente já enxerga o admin criado
    @Synchronized
    fun criarAdminInicial(req: SetupAdminRequest) {

        // Segunda barreira: o setup só pode rodar com o banco sem usuários
        if (existeUsuario()) {
            log.warn("Criação de administrador inicial recusada: já existem usuários cadastrados")
            throw IllegalStateException("O sistema já foi configurado")
        }

        val email = emailValidado(req.email)
        politicaSenha.exigir(req.senha, email, req.nome, "configuração inicial")

        val usuario = UsuarioEntity(
            nome = req.nome,
            email = email,
            senha = encoder.encode(req.senha),
            role = Role.ADMIN
        )

        val salvo = repo.save(usuario)

        val permissoes = Modulo.values().flatMap { modulo ->
            Acao.values().map { acao ->
                PermissaoEntity(
                    modulo = modulo,
                    acao = acao,
                    usuario = salvo
                )
            }
        }

        salvo.permissoes.addAll(permissoes)

        repo.save(salvo)

        log.info("Administrador inicial criado: {}", salvo.email)
        auditoria?.registrar(AreaAuditoria.USUARIOS, "Administrador inicial criado", salvo.email)
    }

    @Transactional
    fun cadastrar(req: CadastroUsuarioRequest) {

        val email = emailValidado(req.email)
        if (repo.existsByEmailIgnoreCase(email)) {
            log.warn("Cadastro de usuário recusado: o email {} já está em uso", email)
            throw IllegalArgumentException("Já existe um usuário com este email")
        }

        politicaSenha.exigir(req.senha, email, req.nome, "cadastro de usuário")

        val roleFinal = if (!existeUsuario()) {
            Role.ADMIN
        } else {
            req.role ?: Role.USER
        }

        val usuario = UsuarioEntity(
            nome = req.nome,
            email = email,
            senha = encoder.encode(req.senha),
            role = roleFinal,
            permissoes = mutableListOf()
        )

        // Valida o perfil CUSTOM antes de gravar, para não sobrar usuário sem permissões em caso de erro
        val modulosCustom = if (roleFinal == Role.CUSTOM) converterModulos(req.telas.orEmpty()) else emptyList()
        val acoesCustom = if (roleFinal == Role.CUSTOM) converterAcoes(req.permissoes.orEmpty()) else emptyList()

        val usuarioSalvo = repo.save(usuario)

        val permissoes = permissoesDoPerfil(roleFinal, modulosCustom, acoesCustom, usuarioSalvo)

        usuarioSalvo.permissoes.addAll(permissoes)

        repo.save(usuarioSalvo)

        log.info(
            "Usuário criado: {} (perfil {}) por {}, permissões: {}",
            usuarioSalvo.email, roleFinal,
            SecurityContextHolder.getContext().authentication?.name ?: "sistema",
            permissoes.joinToString { "${it.modulo}_${it.acao}" }
        )
        auditoria?.registrar(AreaAuditoria.USUARIOS, "Usuário criado", usuarioSalvo.email,
            "Perfil $roleFinal; permissões: ${permissoes.joinToString { "${it.modulo}_${it.acao}" }.ifEmpty { "nenhuma" }}")
    }

    /** Permissões de cada perfil: ADMIN tudo, GESTOR ver/criar/editar, USER só ver, CUSTOM os módulos e ações marcados. */
    private fun permissoesDoPerfil(role: Role, modulosCustom: List<Modulo>, acoesCustom: List<Acao>, usuario: UsuarioEntity): List<PermissaoEntity> {
        val pares: List<Pair<Modulo, Acao>> = when (role) {
            Role.ADMIN -> Modulo.values().flatMap { m -> Acao.values().map { m to it } }
            Role.GESTOR -> modulosComEfeito.flatMap { m -> listOf(Acao.READ, Acao.CREATE, Acao.UPDATE).map { m to it } }
            Role.USER -> modulosComEfeito.map { it to Acao.READ }
            Role.CUSTOM -> modulosCustom.flatMap { m -> acoesCustom.map { m to it } }
        }
        return pares.map { (modulo, acao) -> PermissaoEntity(modulo = modulo, acao = acao, usuario = usuario) }
    }

    /**
     * ADMIN muda nome, perfil e permissões de outro usuário. Se o perfil ou as permissões mudaram, as sessões web
     * dele caem (elas guardam as permissões do login); no coletor valem na próxima ação (conferidas a cada ação).
     */
    @Transactional
    fun editar(id: Long, nome: String, role: Role, telas: List<String>?, acoes: List<String>?, quemEdita: Long) {
        // Trava os ADMINs ativos ANTES de ler o usuário: a regra do último ADMIN conta com dados atuais
        val adminsAtivos = adminsAtivosTravados()
        val usuario = buscarPorId(id)
        if (nome.isBlank()) {
            log.warn("Edição de {} recusada: o nome veio em branco", usuario.email)
            throw IllegalArgumentException("Informe o nome")
        }

        if (usuario.role == Role.ADMIN && role != Role.ADMIN && usuario.ativo && adminsAtivos <= 1) {
            log.warn("Edição de {} recusada: é o último administrador ativo", usuario.email)
            throw IllegalStateException("Este é o último administrador ativo: o sistema ficaria sem ninguém para administrar")
        }
        if (id == quemEdita && role != Role.ADMIN) {
            log.warn("Edição de {} recusada: o administrador tentou tirar o próprio acesso de ADMIN (perfil pedido: {})", usuario.email, role)
            throw IllegalStateException("Você não pode tirar o próprio acesso de administrador")
        }

        val modulosCustom = if (role == Role.CUSTOM) converterModulos(telas.orEmpty()) else emptyList()
        val acoesCustom = if (role == Role.CUSTOM) converterAcoes(acoes.orEmpty()) else emptyList()

        val antes = "${usuario.role} [${usuario.permissoes.joinToString { "${it.modulo}_${it.acao}" }}]"
        // Para decidir se a sessão cai: perfil + CONJUNTO de permissões. O texto acima dependia da ordem (a do
        // formulário não é a do banco) e editar só o nome de um perfil CUSTOM podia deslogar a pessoa à toa
        val acessoAntes = usuario.role to usuario.permissoes.map { "${it.modulo}_${it.acao}" }.toSet()
        usuario.nome = nome.trim()
        usuario.role = role
        usuario.permissoes.clear()
        usuario.permissoes.addAll(permissoesDoPerfil(role, modulosCustom, acoesCustom, usuario))
        repo.save(usuario)

        // A sessão web guarda o perfil e as permissões do login: sem derrubá-la, um ADMIN rebaixado continuaria
        // ADMIN até sair (e poderia, por exemplo, criar outro ADMIN). Só nome mudou: ninguém é deslogado
        val depois = "$role [${usuario.permissoes.joinToString { "${it.modulo}_${it.acao}" }}]"
        val acessoDepois = role to usuario.permissoes.map { "${it.modulo}_${it.acao}" }.toSet()
        val encerradas = if (acessoDepois != acessoAntes && id != quemEdita) sessoes.encerrarWeb(id, "perfil ou permissões alterados") else 0

        log.info(
            "Usuário {} alterado por {}: perfil {} → {} [{}]{}", usuario.email, quemFez(), antes, role,
            usuario.permissoes.joinToString { "${it.modulo}_${it.acao}" },
            if (encerradas > 0) " ($encerradas sessão(ões) web encerrada(s): entra de novo com as permissões novas)" else ""
        )
        auditoria?.registrar(AreaAuditoria.USUARIOS, "Usuário alterado", usuario.email,
            "Antes: $antes. Depois: $role [${usuario.permissoes.joinToString { "${it.modulo}_${it.acao}" }}]")
    }

    /** ADMIN desativa um usuário: ele não entra mais e as sessões abertas dele (web e coletores) caem na hora. */
    @Transactional
    fun desativar(id: Long, quemDesativa: Long) {
        // Trava os ADMINs ativos ANTES de ler o usuário (ver UserRepository.travarAtivosDoPerfil)
        val adminsAtivos = adminsAtivosTravados()
        val usuario = buscarPorId(id)
        if (!usuario.ativo) return

        if (id == quemDesativa) throw IllegalStateException("Você não pode desativar o próprio usuário")
        if (usuario.role == Role.ADMIN && adminsAtivos <= 1) {
            log.warn("Desativação de {} recusada: é o último administrador ativo", usuario.email)
            throw IllegalStateException("Este é o último administrador ativo: o sistema ficaria sem ninguém para administrar")
        }

        usuario.ativo = false
        repo.save(usuario)
        val encerradas = sessoes.encerrar(id, "usuário desativado")
        log.warn("Usuário {} desativado por {} ({} sessão(ões) encerrada(s))", usuario.email, quemFez(), encerradas.total)
        auditoria?.registrar(AreaAuditoria.USUARIOS, "Usuário desativado", usuario.email, "${encerradas.total} sessão(ões) encerrada(s)")
    }

    @Transactional
    fun reativar(id: Long) {
        val usuario = buscarPorId(id)
        if (usuario.ativo) return

        usuario.ativo = true
        repo.save(usuario)
        log.info("Usuário {} reativado por {}", usuario.email, quemFez())
        auditoria?.registrar(AreaAuditoria.USUARIOS, "Usuário reativado", usuario.email)
    }

    fun porId(id: Long): UsuarioEntity = buscarPorId(id)

    /**
     * Quantos ADMINs ativos há, com as linhas deles travadas até o fim da transação: desativar e rebaixar ADMIN
     * ficam em fila, e duas operações ao mesmo tempo não deixam mais o sistema sem nenhum ADMIN.
     */
    private fun adminsAtivosTravados(): Int = repo.travarAtivosDoPerfil(Role.ADMIN).size

    private fun quemFez() =SecurityContextHolder.getContext().authentication?.name ?: "sistema"

    private fun converterModulos(telas: List<String>): List<Modulo> {
        if (telas.isEmpty()) {
            throw IllegalArgumentException("Selecione pelo menos um módulo")
        }
        return telas.map { tela ->
            modulosComEfeito.find { it.name == tela } ?: run {
                log.warn("Cadastro de usuário recusado: módulo inexistente '{}' enviado pelo formulário", tela)
                throw IllegalArgumentException("Módulo inválido: $tela")
            }
        }
    }

    private fun converterAcoes(acoes: List<String>): List<Acao> {
        if (acoes.isEmpty()) {
            throw IllegalArgumentException("Selecione pelo menos uma permissão")
        }
        return acoes.map { acao ->
            Acao.values().find { it.name == acao } ?: run {
                log.warn("Cadastro de usuário recusado: ação inexistente '{}' enviada pelo formulário", acao)
                throw IllegalArgumentException("Permissão inválida: $acao")
            }
        }
    }

    // Busca pelo id (e não pelo email da sessão), assim continua funcionando depois de trocar o email
    @Transactional
    fun alterarSenha(usuarioId: Long, senhaAtual: String, novaSenha: String, confirmarSenha: String, sessaoWebAtual: String? = null) {

        val usuario = buscarPorId(usuarioId)

        conferirSenhaAtual(usuario, senhaAtual, "Troca de senha")

        if (novaSenha != confirmarSenha) {
            throw IllegalArgumentException("A nova senha e a confirmação não conferem")
        }

        if (encoder.matches(novaSenha, usuario.senha)) {
            throw IllegalArgumentException("A nova senha deve ser diferente da atual")
        }

        politicaSenha.exigir(novaSenha, usuario.email, usuario.nome, "troca de senha")

        val eraTemporaria = usuario.trocarSenha
        val tinhaPedido = usuario.senhaPedidaEm != null

        usuario.senha = encoder.encode(novaSenha)
        usuario.trocarSenha = false
        usuario.senhaTemporariaEm = null
        // Quem trocou a própria senha não precisa mais do pedido de "Esqueceu a senha?"
        usuario.senhaPedidaEm = null
        repo.save(usuario)

        // Quem souber a senha antiga perde o acesso: as outras sessões (web e coletores) caem; a atual continua
        sessoes.encerrar(usuarioId, "senha trocada pelo próprio usuário", manterSessaoWeb = sessaoWebAtual)

        log.info("Senha alterada pelo próprio usuário: {}{}{}", usuario.email,
            if (eraTemporaria) " (substituiu a senha temporária)" else "",
            if (tinhaPedido) "; pedido de nova senha em aberto encerrado" else "")
        auditoria?.registrar(AreaAuditoria.SENHAS, "Senha trocada pelo próprio usuário", usuario.email,
            if (eraTemporaria) "Substituiu a senha temporária" else null)
    }

    /** Troca o email (que também é o login). Exige a senha atual. Retorna o email gravado. */
    @Transactional
    fun alterarEmail(usuarioId: Long, novoEmail: String, senhaAtual: String): String {

        val usuario = buscarPorId(usuarioId)
        val emailNovo = normalizarEmail(novoEmail)

        conferirSenhaAtual(usuario, senhaAtual, "Troca de email")

        if (!EMAIL_VALIDO.matches(emailNovo)) {
            throw IllegalArgumentException("Email inválido")
        }

        if (emailNovo.equals(usuario.email, ignoreCase = true)) {
            throw IllegalArgumentException("O novo email é igual ao atual")
        }

        if (repo.existsByEmailIgnoreCase(emailNovo)) {
            log.warn("Troca de email recusada para {}: o email {} já pertence a outro usuário", usuario.email, emailNovo)
            throw IllegalArgumentException("Este email já está em uso por outro usuário")
        }

        val emailAntigo = usuario.email
        usuario.email = emailNovo
        repo.save(usuario)

        log.info("Email alterado pelo próprio usuário: {} -> {}", emailAntigo, emailNovo)
        auditoria?.registrar(AreaAuditoria.USUARIOS, "Email trocado pelo próprio usuário", emailNovo, "Antes: $emailAntigo")
        return emailNovo
    }

    private fun buscarPorId(usuarioId: Long): UsuarioEntity =
        repo.findById(usuarioId).orElseThrow {
            log.error("Usuário logado com id {} não existe mais no banco", usuarioId)
            IllegalStateException("Usuário não encontrado")
        }

    /**
     * Senha atual da troca de senha ou de email: o erro conta como erro de login da origem (IP), e com o bloqueio
     * nem a senha certa passa (igual ao login). Antes dava para testar senhas sem limite numa sessão aberta.
     */
    private fun conferirSenhaAtual(usuario: UsuarioEntity, senhaAtual: String, acao: String) {
        val ip = (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
            as? org.springframework.web.context.request.ServletRequestAttributes)?.request?.remoteAddr
        tentativas?.tempoRestanteDeBloqueio(usuario.email, ip)?.let { restante ->
            val minutos = (restante.toSeconds() + 59) / 60
            log.warn("{} recusada para {}: bloqueada por tentativas erradas (IP {}, mais {} min)", acao, usuario.email, ip, minutos)
            throw IllegalArgumentException("Muitas tentativas com a senha errada. Aguarde $minutos minuto(s) e tente de novo.")
        }
        if (!encoder.matches(senhaAtual, usuario.senha)) {
            tentativas?.registrarFalha(usuario.email, ip)
            log.warn("{} recusada para {}: senha atual incorreta (IP {})", acao, usuario.email, ip)
            throw IllegalArgumentException("Senha atual incorreta")
        }
    }

    /** Email normalizado e conferido (cadastro e configuração inicial). */
    private fun emailValidado(email: String): String {
        val normalizado = normalizarEmail(email)
        if (!EMAIL_VALIDO.matches(normalizado)) {
            log.warn("Cadastro de usuário recusado: email inválido '{}'", normalizado)
            throw IllegalArgumentException("Email inválido")
        }
        return normalizado
    }

    companion object {
        /** Módulos que liberam algo de fato (USUARIO não: gestão de usuários é só do ADMIN). */
        private val modulosComEfeito = Modulo.values().filter { it != Modulo.USUARIO }

        private val EMAIL_VALIDO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        /**
         * O email é o login: sempre gravado sem espaços e em minúsculas, e o login compara sem diferenciar
         * maiúsculas. Antes, quem foi cadastrado como "Ana@Empresa.com " só entrava digitando exatamente assim.
         */
        fun normalizarEmail(email: String): String = email.trim().lowercase()
    }
}
