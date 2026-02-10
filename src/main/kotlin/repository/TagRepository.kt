package org.example.repository

import org.example.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
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

interface LeituraAndroidRepository :
    JpaRepository<LeituraAndroidEntity, Long>,
    JpaSpecificationExecutor<LeituraAndroidEntity> {
    @Query("""
        select cast(l.dataHora as date), count(l)
        from LeituraAndroidEntity l
        group by cast(l.dataHora as date)
        order by cast(l.dataHora as date) desc
    """)
    fun contarPorDia(): List<Array<Any>>

    @Query("""
    select count(l)
    from LeituraAndroidEntity l
""")
    fun contarTotal(): Long

    @Query("""
    select count(l)
    from LeituraAndroidEntity l
    where l.dataHora >= :inicio
""")
    fun contarDesde(@Param("inicio") inicio: LocalDateTime): Long

    @Query("""
    select cast(l.dataHora as date), count(l)
    from LeituraAndroidEntity l
    where l.dataHora >= :inicio
    group by cast(l.dataHora as date)
    order by cast(l.dataHora as date)
""")
    fun contarUltimosDias(@Param("inicio") inicio: LocalDateTime): List<Array<Any>>

    @Query("""
    select l.tipo, count(l)
    from LeituraAndroidEntity l
    group by l.tipo
""")
    fun contarPorTipo(): List<Array<Any>>

    @Query("""
    select l.marca, count(l)
    from LeituraAndroidEntity l
    group by l.marca
""")
    fun contarPorMarca(): List<Array<Any>>


    @Query("""
        SELECT l FROM LeituraAndroidEntity l
        WHERE l.dataHora BETWEEN :inicio AND :fim
        ORDER BY l.dataHora DESC
    """)
    fun buscarPorPeriodo(
        @Param("inicio") inicio: LocalDateTime,
        @Param("fim") fim: LocalDateTime
    ): List<LeituraAndroidEntity>

}

