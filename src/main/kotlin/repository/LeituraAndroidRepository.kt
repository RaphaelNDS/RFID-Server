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

    /**
     * Estimativa do total de leituras pelas estatísticas do PostgreSQL (pg_class.reltuples), sem varrer a tabela:
     * o count() lia os milhões de linhas a cada subida só para uma linha de log. -1 (ou null) = ainda sem
     * estatística (tabela nunca analisada pelo autovacuum).
     */
    @Query(value = "select cast(reltuples as bigint) from pg_class where oid = to_regclass('leituras_android')", nativeQuery = true)
    fun estimativaDoTotal(): Long?

    /** Há pelo menos uma leitura? Para quando ainda não há estimativa (para no primeiro registro, sem varrer a tabela). */
    @Query(value = "select exists(select 1 from leituras_android)", nativeQuery = true)
    fun existeAlguma(): Boolean

    /**
     * Retenção: apaga UM LOTE de até [lote] leituras anteriores a [limite], sem carregá-las na memória, e devolve
     * quantas apagou. Quem chama repete até voltar menos que [lote] (RetencaoLeiturasService). Um DELETE só de
     * milhões de linhas segurava a tabela e o WAL numa transação enorme; em lotes cada transação é curta e as
     * gravações dos coletores seguem entre um lote e outro. A transação fica aqui, uma por lote (a tarefa agendada
     * chama de dentro da própria classe do serviço, sem passar pelo proxy dele). Usa o índice de data_hora da V1.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query(
        value = """delete from leituras_android where id in (
                       select id from leituras_android where data_hora < :limite limit :lote)""",
        nativeQuery = true
    )
    fun apagarLoteAnterioresA(@Param("limite") limite: LocalDateTime, @Param("lote") lote: Int): Int
}
