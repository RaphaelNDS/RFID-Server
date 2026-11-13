package org.example.repository

import org.example.model.CampoTipoEntity
import org.example.model.ValorCampoEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CampoTipoRepository : JpaRepository<CampoTipoEntity, Long> {
    fun findAllByTipoEquipamentoIdOrderByOrdemAscIdAsc(tipoId: Long): List<CampoTipoEntity>
    fun findAllByAtivoTrueOrderByTipoEquipamentoIdAscOrdemAscIdAsc(): List<CampoTipoEntity>
}

interface ValorCampoRepository : JpaRepository<ValorCampoEntity, Long> {
    fun findAllByTagId(tagId: Long): List<ValorCampoEntity>
    fun findAllByTagIdIn(tagIds: Collection<Long>): List<ValorCampoEntity>
    fun existsByCampoId(campoId: Long): Boolean
    fun findAllByCampoId(campoId: Long): List<ValorCampoEntity>

    /** Busca da tela de etiquetas: as etiquetas com algum valor que contém o termo ([padrao] já como "%termo%"). */
    @Query("""select distinct v.tagId from ValorCampoEntity v where lower(v.valor) like :padrao escape '\'""")
    fun tagsComValor(@Param("padrao") padrao: String): List<Long>
}
