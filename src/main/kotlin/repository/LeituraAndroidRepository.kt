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

    /** Reenvio do coletor: a mesma tag com a mesma hora informada pelo coletor já foi gravada. */
    fun existsByTagAndLidoEmColetor(tag: String, lidoEmColetor: LocalDateTime): Boolean

    /**
     * Lote: (tag, hora informada pelo coletor) das leituras já gravadas dessas tags desde [inicio], numa consulta só
     * (reenvio do lote).
     */
    @Query("select l.tag, l.lidoEmColetor from LeituraAndroidEntity l where l.tag in :tags and l.lidoEmColetor >= :inicio")
    fun gravadasDesde(@Param("tags") tags: Collection<String>, @Param("inicio") inicio: LocalDateTime): List<Array<Any>>

    // Retenção: um único DELETE em lote, sem carregar as leituras na memória. A transação fica aqui: a tarefa
    // agendada chamava o @Transactional do serviço de dentro da própria classe (sem passar pelo proxy) e o DELETE
    // falhava com TransactionRequiredException toda vez que a retenção estava ligada
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("delete from LeituraAndroidEntity l where l.dataHora < :limite")
    fun apagarAnterioresA(@Param("limite") limite: LocalDateTime): Int

    /**
     * Retenção em partes: apaga até [lote] leituras anteriores a [limite] (pelo índice de data_hora), cada parte na
     * sua transação. Com o limite de 60 s por comando do banco (application.yml), um DELETE único de milhões de
     * leituras na primeira execução seria cancelado todo dia e nunca terminaria.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(
        value = "delete from leituras_android where id in " +
            "(select id from leituras_android where data_hora < :limite limit :lote)",
        nativeQuery = true
    )
    fun apagarLoteAnterioresA(@Param("limite") limite: LocalDateTime, @Param("lote") lote: Int): Int
}
