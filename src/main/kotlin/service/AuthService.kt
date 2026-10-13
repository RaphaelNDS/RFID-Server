package org.example.service

import org.example.model.Acao
import org.example.model.Modulo
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service("authService")
class AuthService {

    private val log = LoggerFactory.getLogger(javaClass)

    private val modulosValidos = Modulo.values().map { it.name }.toSet()
    private val acoesValidas = Acao.values().map { it.name }.toSet()

    // Nomes inválidos já avisados no log (evita repetir a cada página renderizada)
    private val invalidosAvisados = ConcurrentHashMap.newKeySet<String>()

    fun temPermissao(modulo: String, acao: String): Boolean {

        if (modulo !in modulosValidos || acao !in acoesValidas) {
            if (invalidosAvisados.add("${modulo}_${acao}")) {
                log.error(
                    "Checagem de permissão com módulo/ação inexistente: '{}_{}'. Só o ADMIN vai passar. Módulos válidos: {}; ações válidas: {}",
                    modulo, acao, modulosValidos, acoesValidas
                )
            }
        }

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