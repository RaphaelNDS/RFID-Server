package org.example.repository

import org.example.model.LeituraAndroidEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/**
 * Leituras dos coletores. A tela de leituras usa Specification + paginação (LeituraAndroidConsultaService);
 * o dashboard usa as contagens abaixo, sempre no intervalo semiaberto [inicio, fim):
 * "fim" é o primeiro instante FORA do período (ex.: meia-noite do dia seguinte).
 */
interface LeituraAndroidRepository :
    JpaRepository<LeituraAndroidEntity, Long>,
    JpaSpecificationExecutor<LeituraAndroidEntity> {

    @Query("select count(l) from LeituraAndroidEntity l where l.dataHora >= :inicio")
    fun contarDesde(@Param("inicio") inicio: LocalDateTime): Long

    @Query("select count(l) from LeituraAndroidEntity l where l.dataHora >= :inicio and l.dataHora < :fim")
    fun contarEntre(@Param("inicio") inicio: LocalDateTime, @Param("fim") fim: LocalDateTime): Long

    @Query("""
        select cast(l.dataHora as date), count(l)
        from LeituraAndroidEntity l
        where l.dataHora >= :inicio and l.dataHora < :fim
        group by cast(l.dataHora as date)
        order by cast(l.dataHora as date)
    """)
    fun contarPorDiaEntre(@Param("inicio") inicio: LocalDateTime, @Param("fim") fim: LocalDateTime): List<Array<Any>>

    @Query("""
        select l.tipo, count(l)
        from LeituraAndroidEntity l
        where l.dataHora >= :inicio and l.dataHora < :fim
        group by l.tipo
        order by count(l) desc
    """)
    fun contarPorTipoEntre(@Param("inicio") inicio: LocalDateTime, @Param("fim") fim: LocalDateTime): List<Array<Any?>>

    @Query("""
        select l.marca, count(l)
        from LeituraAndroidEntity l
        where l.dataHora >= :inicio and l.dataHora < :fim
        group by l.marca
        order by count(l) desc
    """)
    fun contarPorMarcaEntre(@Param("inicio") inicio: LocalDateTime, @Param("fim") fim: LocalDateTime): List<Array<Any?>>

    /** Reenvio do coletor: a mesma tag com a mesma hora de leitura já foi gravada. */
    fun existsByTagAndDataHora(tag: String, dataHora: LocalDateTime): Boolean

    // Retenção: um único DELETE em lote, sem carregar as leituras na memória
    @Modifying
    @Query("delete from LeituraAndroidEntity l where l.dataHora < :limite")
    fun apagarAnterioresA(@Param("limite") limite: LocalDateTime): Int
}
