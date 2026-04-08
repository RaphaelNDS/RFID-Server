package org.example.service

import org.example.model.*
import org.example.repository.UserRepository
import org.example.request.CadastroUsuarioRequest
import org.example.request.SetupAdminRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class UsuarioService(
    private val repo: UserRepository,
    private val encoder: PasswordEncoder
) {

    fun existeUsuario(): Boolean {
        return repo.count() > 0
    }

    fun listarTodos(): List<UsuarioEntity> {
        return repo.findAll()
    }

    fun criarAdminInicial(req: SetupAdminRequest) {

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
    }

    fun cadastrar(req: CadastroUsuarioRequest) {

        if (repo.existsByEmail(req.email)) {
            throw RuntimeException("Email já cadastrado")
        }

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

                permissoes.add(PermissaoEntity(modulo = Modulo.LEITURA, acao = Acao.READ, usuario = usuarioSalvo))
            }

            Role.CUSTOM -> {

                if (req.telas.isEmpty()) {
                    throw RuntimeException("Selecione pelo menos um módulo")
                }

                if (req.permissoes.isEmpty()) {
                    throw RuntimeException("Selecione pelo menos uma permissão")
                }

                req.telas.forEach { tela ->

                    val modulo = Modulo.valueOf(tela)

                    req.permissoes.forEach { acaoStr ->

                        val acao = Acao.valueOf(acaoStr)

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
    }

    fun alterarSenha(senhaAtual: String, novaSenha: String) {

        val email = SecurityContextHolder.getContext().authentication.name

        val usuario = repo.findByEmail(email)
            ?: throw RuntimeException("Usuário não encontrado")

        if (!encoder.matches(senhaAtual, usuario.senha)) {
            throw RuntimeException("Senha atual inválida")
        }

        usuario.senha = encoder.encode(novaSenha)

        repo.save(usuario)
    }

    fun alterarEmail(novoEmail: String) {

        val email = SecurityContextHolder.getContext().authentication.name

        val usuario = repo.findByEmail(email)
            ?: throw RuntimeException("Usuário não encontrado")

        usuario.email = novoEmail

        repo.save(usuario)
    }
}