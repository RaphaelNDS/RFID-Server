package org.example.repository

import org.example.model.TagEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface TagRepository : JpaRepository<TagEntity, Long> {

    fun findByTagHash(tagHash: String): TagEntity?

    fun findByTagReal(tagReal: String): Optional<TagEntity>

    fun existsByTagReal(tagReal: String): Boolean

    fun findByPatrimonio(patrimonio: String): Optional<TagEntity>

    // Lista: patrimônio repetido em etiquetas antigas (antes da regra de patrimônio único) não pode dar erro 500
    fun findAllByPatrimonio(patrimonio: String): List<TagEntity>

    fun findAllByTagRealIn(tags: Collection<String>): List<TagEntity>

    /**
     * Tela de etiquetas: uma página, com busca ([padrao] vazio = todas; senão "%termo%" em minúsculas, com % e _
     * escapados) no EPC, código, patrimônio e série, ou pelos ids de tipo/marca/modelo cujo nome bate com a busca.
     */
    @Query(
        """select t from TagEntity t where :padrao = ''
           or lower(t.tagReal) like :padrao escape '\'
           or lower(t.codigoInterno) like :padrao escape '\'
           or lower(t.patrimonio) like :padrao escape '\'
           or lower(t.numeroSerie) like :padrao escape '\'
           or t.tipoEquipamentoId in :tipos or t.marcaId in :marcas or t.modeloId in :modelos"""
    )
    fun buscar(
        @Param("padrao") padrao: String,
        @Param("tipos") tipos: Collection<Long>,
        @Param("marcas") marcas: Collection<Long>,
        @Param("modelos") modelos: Collection<Long>,
        pagina: Pageable
    ): Page<TagEntity>

    // Quantas etiquetas usam cada item do catálogo (para impedir exclusão de item em uso)
    fun countByTipoEquipamentoId(tipoId: Long): Long

    fun countByMarcaId(marcaId: Long): Long

    fun countByModeloId(modeloId: Long): Long
}
