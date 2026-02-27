package org.example.service

import org.apache.tomcat.util.net.openssl.ciphers.Authentication
import org.example.model.Role
import org.example.model.UsuarioEntity
import org.example.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val usuarioRepository: UserRepository
) {

    fun temPermissao(modulo: String, acao: String): Boolean {
        val auth = SecurityContextHolder.getContext().authentication
            ?: return false

        val email = auth.name

        val usuario = usuarioRepository.findByEmailFetchPermissoes(email)
            ?: return false

        if (usuario.role == Role.ADMIN) return true

        return usuario.permissoes.any {
            it.modulo.name == modulo && it.acao.name == acao
        }
    }

    fun temRole(role: String): Boolean {
        val auth = SecurityContextHolder.getContext().authentication ?: return false
        return auth.authorities.any { it.authority == "ROLE_$role" }
    }
}