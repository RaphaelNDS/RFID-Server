package org.example.repository

import org.example.model.TagNaoCadastradaEntity
import org.springframework.data.jpa.repository.JpaRepository

/** Etiquetas lidas pelos coletores que ainda não foram cadastradas (tela Pendentes). */
interface TagNaoCadastradaRepository : JpaRepository<TagNaoCadastradaEntity, Long> {

    fun existsByTag(tag: String): Boolean

    fun deleteByTag(tag: String)
}
