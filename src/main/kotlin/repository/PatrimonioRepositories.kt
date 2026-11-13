package org.example.repository

import org.example.model.UnidadeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UnidadeRepository : JpaRepository<UnidadeEntity, Long> {
    fun findAllByOrderByNomeAsc(): List<UnidadeEntity>

    @Query("select u from UnidadeEntity u where lower(u.nome) = lower(:nome)")
    fun porNome(@Param("nome") nome: String): UnidadeEntity?
}

