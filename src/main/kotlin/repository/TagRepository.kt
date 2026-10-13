package org.example.repository

import org.example.model.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.*

/* =====================================================
   TAG
===================================================== */

interface TagRepository : JpaRepository<TagEntity, Long> {

    fun findByTagHash(tagHash: String): TagEntity?

    fun findByTagReal(tagReal: String): Optional<TagEntity>

    fun existsByTagReal(tagReal: String): Boolean

    fun findByPatrimonio(patrimonio: String): Optional<TagEntity>

    // Quantas etiquetas usam cada item do catálogo (para impedir exclusão de item em uso)
    fun countByTipoEquipamentoId(tipoId: Long): Long

    fun countByMarcaId(marcaId: Long): Long

    fun countByModeloId(modeloId: Long): Long
}

/* =====================================================
   TAG NÃO CADASTRADA
===================================================== */

interface TagNaoCadastradaRepository :
    JpaRepository<TagNaoCadastradaEntity, Long> {

    fun existsByTag(tag: String): Boolean

    fun deleteByTag(tag: String)
}

/* =====================================================
   CATÁLOGO
===================================================== */

interface TipoEquipamentoRepository :
    JpaRepository<TipoEquipamentoEntity, Long> {

    fun findByNomeIgnoreCase(nome: String): Optional<TipoEquipamentoEntity>

    fun existsByNomeIgnoreCase(nome: String): Boolean
}

interface MarcaRepository :
    JpaRepository<MarcaEntity, Long> {

    fun findByTipoId(tipoId: Long): List<MarcaEntity>

    fun findByNomeIgnoreCaseAndTipoId(
        nome: String,
        tipoId: Long
    ): Optional<MarcaEntity>

    fun existsByTipoId(tipoId: Long): Boolean
}

interface ModeloRepository :
    JpaRepository<ModeloEntity, Long> {

    fun findByMarcaId(marcaId: Long): List<ModeloEntity>

    fun findByNomeIgnoreCaseAndMarcaId(
        nome: String,
        marcaId: Long
    ): Optional<ModeloEntity>

    fun existsByMarcaId(marcaId: Long): Boolean

    fun countByMarcaTipoId(tipoId: Long): Long
}

/* =====================================================
   LEITURA ANDROID (DASHBOARD)
===================================================== */

interface LeituraAndroidRepository :
    JpaRepository<LeituraAndroidEntity, Long>,
    JpaSpecificationExecutor<LeituraAndroidEntity> {

    /* ===== GRÁFICO POR DIA ===== */

    @Query("""
        select cast(l.dataHora as date), count(l)
        from LeituraAndroidEntity l
        group by cast(l.dataHora as date)
        order by cast(l.dataHora as date)
    """)
    fun contarPorDia(): List<Array<Any>>

    /* ===== CONTADOR DESDE DATA ===== */

    @Query("""
        select count(l)
        from LeituraAndroidEntity l
        where l.dataHora >= :inicio
    """)
    fun contarDesde(
        @Param("inicio") inicio: LocalDateTime
    ): Long

    /* ===== DASHBOARD: CONTAGENS NO PERÍODO [inicio, fim) ===== */
    // Intervalo semiaberto: "fim" é o primeiro instante FORA do período (ex.: meia-noite do dia seguinte)

    @Query("""
        select count(l)
        from LeituraAndroidEntity l
        where l.dataHora >= :inicio and l.dataHora < :fim
    """)
    fun contarEntre(
        @Param("inicio") inicio: LocalDateTime,
        @Param("fim") fim: LocalDateTime
    ): Long

    @Query("""
        select cast(l.dataHora as date), count(l)
        from LeituraAndroidEntity l
        where l.dataHora >= :inicio and l.dataHora < :fim
        group by cast(l.dataHora as date)
        order by cast(l.dataHora as date)
    """)
    fun contarPorDiaEntre(
        @Param("inicio") inicio: LocalDateTime,
        @Param("fim") fim: LocalDateTime
    ): List<Array<Any>>

    @Query("""
        select l.tipo, count(l)
        from LeituraAndroidEntity l
        where l.dataHora >= :inicio and l.dataHora < :fim
        group by l.tipo
        order by count(l) desc
    """)
    fun contarPorTipoEntre(
        @Param("inicio") inicio: LocalDateTime,
        @Param("fim") fim: LocalDateTime
    ): List<Array<Any?>>

    @Query("""
        select l.marca, count(l)
        from LeituraAndroidEntity l
        where l.dataHora >= :inicio and l.dataHora < :fim
        group by l.marca
        order by count(l) desc
    """)
    fun contarPorMarcaEntre(
        @Param("inicio") inicio: LocalDateTime,
        @Param("fim") fim: LocalDateTime
    ): List<Array<Any?>>
}





















/*
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


interface TipoEquipamentoRepository : JpaRepository<TipoEquipamentoEntity, Long> {

    fun findByNomeIgnoreCase(nome: String): Optional<TipoEquipamentoEntity>

}

interface MarcaRepository : JpaRepository<MarcaEntity, Long> {

    fun findByTipoId(tipoId: Long): List<MarcaEntity>

    fun findByNomeIgnoreCaseAndTipoId(nome: String, tipoId: Long): Optional<MarcaEntity>
}


//interface MarcaRepository : JpaRepository<MarcaEntity, Long> {
//
//    fun findByNomeIgnoreCaseAndTipoId(nome: String, tipoId: Long): Optional<MarcaEntity>
//
//    fun findByTipoId(tipoId: Long): List<MarcaEntity>
//}

interface ModeloRepository : JpaRepository<ModeloEntity, Long> {

    fun findByMarcaId(marcaId: Long): List<ModeloEntity>

    fun findByNomeIgnoreCaseAndMarcaId(nome: String, marcaId: Long): Optional<ModeloEntity>

    fun countByMarcaTipoId(tipoId: Long): Long


//    fun findByNomeIgnoreCaseAndMarcaId(nome: String, marcaId: Long): Optional<ModeloEntity>
//
//    fun findByMarcaId(marcaId: Long): List<ModeloEntity>
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

*/

