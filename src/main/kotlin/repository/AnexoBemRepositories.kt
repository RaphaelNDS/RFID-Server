package org.example.repository

import org.example.model.AnexoBemConteudoEntity
import org.example.model.AnexoBemEntity
import org.springframework.data.jpa.repository.JpaRepository

interface AnexoBemRepository : JpaRepository<AnexoBemEntity, Long> {
    /** Mais antigos primeiro (a ordem em que foram enviados). */
    fun findAllByTagIdOrderByIdAsc(tagId: Long): List<AnexoBemEntity>

    fun countByTagId(tagId: Long): Long
}

interface AnexoBemConteudoRepository : JpaRepository<AnexoBemConteudoEntity, Long>
