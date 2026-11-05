package org.example.repository

import org.example.model.TagNaoCadastradaEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** Etiquetas lidas pelos coletores que ainda não foram cadastradas (tela Pendentes). */
interface TagNaoCadastradaRepository : JpaRepository<TagNaoCadastradaEntity, Long> {

    fun existsByTag(tag: String): Boolean

    fun deleteByTag(tag: String)

    /** Lote: quais destas tags já estão pendentes, numa consulta só. */
    @Query("select n.tag from TagNaoCadastradaEntity n where n.tag in :tags")
    fun jaPendentes(@Param("tags") tags: Collection<String>): List<String>

    /** Tela Pendentes: uma página, com busca pelo EPC ([padrao] vazio = todas). */
    @Query("""select n from TagNaoCadastradaEntity n where :padrao = '' or lower(n.tag) like :padrao escape '\'""")
    fun buscar(@Param("padrao") padrao: String, pagina: Pageable): Page<TagNaoCadastradaEntity>
}
