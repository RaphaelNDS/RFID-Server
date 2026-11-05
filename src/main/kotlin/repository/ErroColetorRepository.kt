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

    /** Os mais recentes do coletor numa versão do app: filtrado no banco (antes só entre os 200 mais recentes). */
    fun findTop200ByColetorAndVersaoAppOrderByMomentoDesc(coletor: String, versaoApp: String): List<ErroColetorEntity>

    /** Todas as versões do app com erro guardado deste coletor (filtro da tela), da mais nova para a mais antiga. */
    @Query("select e.versaoApp from ErroColetorEntity e where e.coletor = :coletor and e.versaoApp is not null group by e.versaoApp order by max(e.momento) desc")
    fun versoesComErro(@Param("coletor") coletor: String): List<String>

    /** [coletor, quantidade] desde [desde] (coluna "Erros (24 h)" da tela Coletores). */
    @Query("select e.coletor, count(e) from ErroColetorEntity e where e.momento >= :desde group by e.coletor")
    fun contarPorColetorDesde(@Param("desde") desde: LocalDateTime): List<Array<Any>>

    // Retenção: um único DELETE em lote
    @Modifying
    @Query("delete from ErroColetorEntity e where e.recebidoEm < :limite")
    fun apagarRecebidosAntesDe(@Param("limite") limite: LocalDateTime): Int
}
