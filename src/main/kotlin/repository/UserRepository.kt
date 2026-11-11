package org.example.repository

import jakarta.persistence.LockModeType
import org.example.model.Role
import org.example.model.UsuarioEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRepository : JpaRepository<UsuarioEntity, Long> {

    fun findByEmail(email: String): UsuarioEntity?

    fun existsByEmail(email: String): Boolean

    fun existsByEmailIgnoreCase(email: String): Boolean

    /**
     * Para nunca desativar (ou rebaixar) o último ADMIN ativo.
     * Os usuários ativos do perfil, TRAVADOS (select ... for update) até o fim da transação. Desativar ou rebaixar
     * um ADMIN conta os ADMINs ativos com esta trava: duas operações ao mesmo tempo (ex.: dois ADMINs desativando um
     * ao outro) contavam 2 cada uma e o sistema ficava sem nenhum ADMIN. Com a trava, a segunda espera a primeira
     * terminar e já conta sem o que ela mudou.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UsuarioEntity u where u.role = :role and u.ativo = true order by u.id")
    fun travarAtivosDoPerfil(@Param("role") role: Role): List<UsuarioEntity>

    fun findByEmailIgnoreCase(email: String): UsuarioEntity?

    /** Pedidos de senha nova em aberto, o mais antigo primeiro. */
    fun findBySenhaPedidaEmIsNotNullOrderBySenhaPedidaEm(): List<UsuarioEntity>

    fun countBySenhaPedidaEmIsNotNull(): Long

    @Query("""
        SELECT u FROM UsuarioEntity u
        LEFT JOIN FETCH u.permissoes
        WHERE lower(u.email) = lower(:email)
    """)
    fun findByEmailFetchPermissoes(@Param("email") email: String): UsuarioEntity?
}
