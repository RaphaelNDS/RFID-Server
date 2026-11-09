package org.example.repository

import org.example.model.TransferenciaEntity
import org.example.model.TransferenciaItemEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TransferenciaRepository : JpaRepository<TransferenciaEntity, Long> {
    /** Mais novas primeiro; filtro opcional por etiqueta (EPC exato) ou patrimônio. */
    @Query(
        value = """select t from TransferenciaEntity t where (:tag = '' or exists (select 1 from TransferenciaItemEntity i
                   where i.transferenciaId = t.id and (i.tagReal = :tag or lower(i.patrimonio) = lower(:tag))))
                   order by t.data desc, t.id desc""",
        countQuery = """select count(t) from TransferenciaEntity t where (:tag = '' or exists (select 1 from TransferenciaItemEntity i
                   where i.transferenciaId = t.id and (i.tagReal = :tag or lower(i.patrimonio) = lower(:tag))))"""
    )
    fun buscar(@Param("tag") tag: String, pagina: Pageable): Page<TransferenciaEntity>

    fun findAllByInventarioIdOrderByIdDesc(inventarioId: String): List<TransferenciaEntity>
}

interface TransferenciaItemRepository : JpaRepository<TransferenciaItemEntity, Long> {
    fun findAllByTransferenciaIdOrderByIdAsc(transferenciaId: Long): List<TransferenciaItemEntity>

    /** Quantos bens por guia (lista de transferências). */
    @Query("select i.transferenciaId, count(i) from TransferenciaItemEntity i where i.transferenciaId in :ids group by i.transferenciaId")
    fun contarPorTransferencia(@Param("ids") ids: Collection<Long>): List<Array<Any>>
}
