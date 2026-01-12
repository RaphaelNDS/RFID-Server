package org.example.repository

import org.example.model.TagEntity
import org.example.model.TagNaoCadastradaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface TagRepository : JpaRepository<TagEntity, Long> {

    fun findByTagHash(tagHash: String): TagEntity?

    fun findByTagReal(epc: String): Optional<TagEntity>

    fun existsByTagReal(tag: String): Boolean
}


interface TagNaoCadastradaRepository :
    JpaRepository<TagNaoCadastradaEntity, Long> {

    fun existsByTag(tag: String): Boolean
    fun deleteByTag(tag: String)
}
