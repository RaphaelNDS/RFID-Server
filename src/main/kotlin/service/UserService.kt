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
    private val politicaSenha: PoliticaSenha
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

        val permissoes = mutableListOf<PermissaoEntity>()

        when (roleFinal) {

            Role.ADMIN -> {

                Modulo.values().forEach { modulo ->
                    Acao.values().forEach { acao ->
                        permissoes.add(
                            PermissaoEntity(
                                modulo = modulo,
                                acao = acao,
                                usuario = usuarioSalvo
                            )
                        )
                    }
                }
            }

            Role.GESTOR -> {

                Modulo.values().forEach { modulo ->

                    permissoes.add(PermissaoEntity(modulo = modulo, acao = Acao.READ, usuario = usuarioSalvo))
                    permissoes.add(PermissaoEntity(modulo = modulo, acao = Acao.CREATE, usuario = usuarioSalvo))
                    permissoes.add(PermissaoEntity(modulo = modulo, acao = Acao.UPDATE, usuario = usuarioSalvo))
                }
            }

            Role.USER -> {

                Modulo.values().forEach { modulo ->

                    permissoes.add(
                        PermissaoEntity(
                         modulo = modulo,
                          acao = Acao.READ,
                           usuario = usuarioSalvo
                        )
                    )
                }
            }

            Role.CUSTOM -> {

                modulosCustom.forEach { modulo ->
                    acoesCustom.forEach { acao ->
                        permissoes.add(
                            PermissaoEntity(
                                modulo = modulo,
                                acao = acao,
                                usuario = usuarioSalvo
                            )
                        )
                    }
                }
            }
        }

        usuarioSalvo.permissoes.addAll(permissoes)

        repo.save(usuarioSalvo)

        log.info(
            "Usuário criado: {} (perfil {}) por {}, permissões: {}",
            usuarioSalvo.email, roleFinal,
            SecurityContextHolder.getContext().authentication?.name ?: "sistema",
            permissoes.joinToString { "${it.modulo}_${it.acao}" }
        )
    }

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
    fun alterarSenha(usuarioId: Long, senhaAtual: String, novaSenha: String, confirmarSenha: String) {

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

        usuario.senha = encoder.encode(novaSenha)
        repo.save(usuario)

        log.info("Senha alterada pelo próprio usuário: {}", usuario.email)
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
