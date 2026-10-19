package org.example.repository

import org.example.model.UsuarioEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRepository : JpaRepository<UsuarioEntity, Long> {

    fun findByEmail(email: String): UsuarioEntity?

    fun existsByEmail(email: String): Boolean

    fun existsByEmailIgnoreCase(email: String): Boolean

    fun findByEmailIgnoreCase(email: String): UsuarioEntity?

    /** Pedidos de senha nova em aberto, o mais antigo primeiro. */
    fun findBySenhaPedidaEmIsNotNullOrderBySenhaPedidaEm(): List<UsuarioEntity>

    fun countBySenhaPedidaEmIsNotNull(): Long

    @Query("""
        SELECT u FROM UsuarioEntity u
        LEFT JOIN FETCH u.permissoes
        WHERE u.email = :email
    """)
    fun findByEmailFetchPermissoes(@Param("email") email: String): UsuarioEntity?
}
