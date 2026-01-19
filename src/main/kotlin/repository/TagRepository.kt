package org.example.repository

import org.example.model.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface TagRepository : JpaRepository<TagEntity, Long> {

    fun findByTagHash(tagHash: String): TagEntity?

    fun findByTagReal(epc: String): Optional<TagEntity>

    fun existsByTagReal(tag: String): Boolean

    fun findByPatrimonio(patrimonio: String): Optional<TagEntity>
}


interface TagNaoCadastradaRepository :
    JpaRepository<TagNaoCadastradaEntity, Long> {

    fun existsByTag(tag: String): Boolean
    fun deleteByTag(tag: String)
}


interface TipoEquipamentoRepository :
    JpaRepository<TipoEquipamentoEntity, Long>

interface MarcaRepository : JpaRepository<MarcaEntity, Long> {
    fun findByTipoId(tipoId: Long): List<MarcaEntity>
}


interface ModeloRepository : JpaRepository<ModeloEntity, Long> {
    fun findByMarcaId(marcaId: Long): List<ModeloEntity>
}
