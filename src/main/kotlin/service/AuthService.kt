package org.example.service

import org.apache.tomcat.util.net.openssl.ciphers.Authentication
import org.example.model.Role
import org.example.model.UsuarioEntity
import org.example.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

@Service("authService")
class AuthService {


    fun temPermissao(modulo: String, acao: String): Boolean {

        val auth = SecurityContextHolder.getContext().authentication ?: return false

        if (isAdmin()) return true

        val permissao = "${modulo}_${acao}"

        return auth.authorities.any {
            it.authority == permissao
        }
    }

    fun temRole(role: String): Boolean {

        val auth = SecurityContextHolder.getContext().authentication ?: return false

        return auth.authorities.any {
            it.authority == "ROLE_$role"
        }
    }

    fun isAdmin(): Boolean {

        val auth = SecurityContextHolder.getContext().authentication ?: return false

        return auth.authorities.any {
            it.authority == "ROLE_ADMIN"
        }
    }

    fun temAlgumaPermissao(vararg permissoes: String): Boolean {

        val auth = SecurityContextHolder.getContext().authentication ?: return false

        if (isAdmin()) return true

        return permissoes.any { perm ->
            auth.authorities.any { it.authority == perm }
        }
    }

    fun temTodasPermissoes(vararg permissoes: String): Boolean {

        val auth = SecurityContextHolder.getContext().authentication ?: return false

        if (isAdmin()) return true

        return permissoes.all { perm ->
            auth.authorities.any { it.authority == perm }
        }
    }


    fun temPermissaoDireta(permissao: String): Boolean {

        val auth = SecurityContextHolder.getContext().authentication ?: return false

        if (isAdmin()) return true

        return auth.authorities.any {
            it.authority == permissao
        }
    }
}