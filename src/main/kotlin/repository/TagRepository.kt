package org.example.repository

import org.example.model.TagEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface TagRepository : JpaRepository<TagEntity, Long> {

    fun findByTagHash(tagHash: String): TagEntity?

    fun findByTagReal(tagReal: String): Optional<TagEntity>

    fun existsByTagReal(tagReal: String): Boolean

    fun findByPatrimonio(patrimonio: String): Optional<TagEntity>

    // Lista: patrimônio repetido em etiquetas antigas (antes da regra de patrimônio único) não pode dar erro 500
    fun findAllByPatrimonio(patrimonio: String): List<TagEntity>

    fun findAllByTagRealIn(tags: Collection<String>): List<TagEntity>

    // Quantas etiquetas usam cada item do catálogo (para impedir exclusão de item em uso)
    fun countByTipoEquipamentoId(tipoId: Long): Long

    fun countByMarcaId(marcaId: Long): Long

    fun countByModeloId(modeloId: Long): Long
}
