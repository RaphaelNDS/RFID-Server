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
    private val sessoes: SessoesUsuarioService
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

        politicaSenha.exigir(req.senha, req.email, req.nome, "configuração inicial")

        val usuario = UsuarioEntity(
            nome = req.nome,
            email = req.email,
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
    }

    @Transactional
    fun cadastrar(req: CadastroUsuarioRequest) {

        if (repo.existsByEmailIgnoreCase(req.email.trim())) {
            log.warn("Cadastro de usuário recusado: o email {} já está em uso", req.email)
            throw IllegalArgumentException("Já existe um usuário com este email")
        }

        politicaSenha.exigir(req.senha, req.email, req.nome, "cadastro de usuário")

        val roleFinal = if (!existeUsuario()) {
            Role.ADMIN
        } else {
            req.role ?: Role.USER
        }

        val usuario = UsuarioEntity(
            nome = req.nome,
            email = req.email,
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
    }

    /** Permissões de cada perfil: ADMIN tudo, GESTOR ver/criar/editar, USER só ver, CUSTOM os módulos e ações marcados. */
    private fun permissoesDoPerfil(role: Role, modulosCustom: List<Modulo>, acoesCustom: List<Acao>, usuario: UsuarioEntity): List<PermissaoEntity> {
        val pares: List<Pair<Modulo, Acao>> = when (role) {
            Role.ADMIN -> Modulo.values().flatMap { m -> Acao.values().map { m to it } }
            Role.GESTOR -> Modulo.values().flatMap { m -> listOf(Acao.READ, Acao.CREATE, Acao.UPDATE).map { m to it } }
            Role.USER -> Modulo.values().map { it to Acao.READ }
            Role.CUSTOM -> modulosCustom.flatMap { m -> acoesCustom.map { m to it } }
        }
        return pares.map { (modulo, acao) -> PermissaoEntity(modulo = modulo, acao = acao, usuario = usuario) }
    }

    /**
     * ADMIN muda nome, perfil e permissões de outro usuário. As permissões novas valem no próximo login na web
     * (a sessão aberta guarda as antigas) e na próxima ação no coletor (lá são conferidas a cada ação).
     */
    @Transactional
    fun editar(id: Long, nome: String, role: Role, telas: List<String>?, acoes: List<String>?, quemEdita: Long) {
        val usuario = buscarPorId(id)
        if (nome.isBlank()) {
            log.warn("Edição de {} recusada: o nome veio em branco", usuario.email)
            throw IllegalArgumentException("Informe o nome")
        }

        if (usuario.role == Role.ADMIN && role != Role.ADMIN && usuario.ativo && repo.countByRoleAndAtivoTrue(Role.ADMIN) <= 1) {
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
        usuario.nome = nome.trim()
        usuario.role = role
        usuario.permissoes.clear()
        usuario.permissoes.addAll(permissoesDoPerfil(role, modulosCustom, acoesCustom, usuario))
        repo.save(usuario)

        log.info(
            "Usuário {} alterado por {}: perfil {} → {} [{}]", usuario.email, quemFez(), antes, role,
            usuario.permissoes.joinToString { "${it.modulo}_${it.acao}" }
        )
    }

    /** ADMIN desativa um usuário: ele não entra mais e as sessões abertas dele (web e coletores) caem na hora. */
    @Transactional
    fun desativar(id: Long, quemDesativa: Long) {
        val usuario = buscarPorId(id)
        if (!usuario.ativo) return

        if (id == quemDesativa) throw IllegalStateException("Você não pode desativar o próprio usuário")
        if (usuario.role == Role.ADMIN && repo.countByRoleAndAtivoTrue(Role.ADMIN) <= 1) {
            log.warn("Desativação de {} recusada: é o último administrador ativo", usuario.email)
            throw IllegalStateException("Este é o último administrador ativo: o sistema ficaria sem ninguém para administrar")
        }

        usuario.ativo = false
        repo.save(usuario)
        val encerradas = sessoes.encerrar(id, "usuário desativado")
        log.warn("Usuário {} desativado por {} ({} sessão(ões) encerrada(s))", usuario.email, quemFez(), encerradas.total)
    }

    @Transactional
    fun reativar(id: Long) {
        val usuario = buscarPorId(id)
        if (usuario.ativo) return

        usuario.ativo = true
        repo.save(usuario)
        log.info("Usuário {} reativado por {}", usuario.email, quemFez())
    }

    fun porId(id: Long): UsuarioEntity = buscarPorId(id)

    private fun quemFez() = SecurityContextHolder.getContext().authentication?.name ?: "sistema"

    private fun converterModulos(telas: List<String>): List<Modulo> {
        if (telas.isEmpty()) {
            throw IllegalArgumentException("Selecione pelo menos um módulo")
        }
        return telas.map { tela ->
            Modulo.values().find { it.name == tela } ?: run {
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

        if (!encoder.matches(senhaAtual, usuario.senha)) {
            log.warn("Troca de senha recusada para {}: senha atual incorreta", usuario.email)
            throw IllegalArgumentException("Senha atual incorreta")
        }

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
        // Quem trocou a própria senha não precisa mais do pedido de "Esqueceu a senha?"
        usuario.senhaPedidaEm = null
        repo.save(usuario)

        // Quem souber a senha antiga perde o acesso: as outras sessões (web e coletores) caem; a atual continua
        sessoes.encerrar(usuarioId, "senha trocada pelo próprio usuário", manterSessaoWeb = sessaoWebAtual)

        log.info("Senha alterada pelo próprio usuário: {}{}{}", usuario.email,
            if (eraTemporaria) " (substituiu a senha temporária)" else "",
            if (tinhaPedido) "; pedido de nova senha em aberto encerrado" else "")
    }

    /** Troca o email (que também é o login). Exige a senha atual. Retorna o email gravado. */
    @Transactional
    fun alterarEmail(usuarioId: Long, novoEmail: String, senhaAtual: String): String {

        val usuario = buscarPorId(usuarioId)
        val emailNovo = novoEmail.trim()

        if (!encoder.matches(senhaAtual, usuario.senha)) {
            log.warn("Troca de email recusada para {}: senha atual incorreta", usuario.email)
            throw IllegalArgumentException("Senha atual incorreta")
        }

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
        return emailNovo
    }

    private fun buscarPorId(usuarioId: Long): UsuarioEntity =
        repo.findById(usuarioId).orElseThrow {
            log.error("Usuário logado com id {} não existe mais no banco", usuarioId)
            IllegalStateException("Usuário não encontrado")
        }

    companion object {
        private val EMAIL_VALIDO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
