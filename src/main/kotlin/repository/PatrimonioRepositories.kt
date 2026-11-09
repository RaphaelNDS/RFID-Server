package org.example.repository

import org.example.model.ResponsavelEntity
import org.example.model.UnidadeEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UnidadeRepository : JpaRepository<UnidadeEntity, Long> {
    fun findAllByOrderByNomeAsc(): List<UnidadeEntity>

    @Query("select u from UnidadeEntity u where lower(u.nome) = lower(:nome)")
    fun porNome(@Param("nome") nome: String): UnidadeEntity?
}

interface ResponsavelRepository : JpaRepository<ResponsavelEntity, Long> {
    fun findAllByOrderByNomeAsc(): List<ResponsavelEntity>

    @Query("select r from ResponsavelEntity r where lower(r.matricula) = lower(:matricula)")
    fun porMatricula(@Param("matricula") matricula: String): ResponsavelEntity?

    @Query("select r from ResponsavelEntity r where lower(r.nome) = lower(:nome) order by r.ativo desc, r.id")
    fun porNome(@Param("nome") nome: String): List<ResponsavelEntity>
}
