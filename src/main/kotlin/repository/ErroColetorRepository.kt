package org.example.repository

import org.example.model.ErroColetorEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ErroColetorRepository : JpaRepository<ErroColetorEntity, String> {

    /** Os mais recentes do coletor (tela de erros do coletor). */
    fun findTop200ByColetorOrderByMomentoDesc(coletor: String): List<ErroColetorEntity>

    /** [coletor, quantidade] desde [desde] (coluna "Erros (24 h)" da tela Coletores). */
    @Query("select e.coletor, count(e) from ErroColetorEntity e where e.momento >= :desde group by e.coletor")
    fun contarPorColetorDesde(@Param("desde") desde: LocalDateTime): List<Array<Any>>

    // Retenção: um único DELETE em lote
    @Modifying
    @Query("delete from ErroColetorEntity e where e.recebidoEm < :limite")
    fun apagarRecebidosAntesDe(@Param("limite") limite: LocalDateTime): Int
}
