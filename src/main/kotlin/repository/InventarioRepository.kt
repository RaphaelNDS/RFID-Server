package org.example.repository

import org.example.model.InventarioEntity
import org.example.model.InventarioItemEntity
import org.example.model.LocalEntity
import org.example.model.StatusInventario
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface LocalRepository : JpaRepository<LocalEntity, Long> {

    /** O mais antigo com esse nome: se o banco tiver duplicados de antes da V17, não dá erro (era Optional único). */
    fun findFirstByNomeIgnoreCaseOrderByIdAsc(nome: String): Optional<LocalEntity>

    fun findAllByOrderByNomeAsc(): List<LocalEntity>
}

interface InventarioRepository : JpaRepository<InventarioEntity, String> {

    /** Tela de inventários: uma página (a ordem vem no [pagina]). */
    fun findAllBy(pagina: Pageable): Page<InventarioEntity>

    fun findByLocalId(localId: Long, pagina: Pageable): Page<InventarioEntity>

    /** Último inventário finalizado do local: base da lista do que é esperado no próximo inventário. */
    fun findFirstByLocalIdAndStatusOrderByFinalizadoEmDesc(localId: Long, status: StatusInventario): InventarioEntity?

    /** Inventário finalizado do local imediatamente antes de [antes] (outro que não [id]): base da comparação na web.
     *  Empate no horário de fim: vale o de maior id, para o resultado não variar. */
    fun findFirstByLocalIdAndStatusAndFinalizadoEmBeforeAndIdNotOrderByFinalizadoEmDescIdDesc(
        localId: Long, status: StatusInventario, antes: java.time.LocalDateTime, id: String
    ): InventarioEntity?
}

interface JustificativaInventarioRepository : JpaRepository<org.example.model.JustificativaInventarioEntity, Long> {
    fun findByInventarioId(inventarioId: String): List<org.example.model.JustificativaInventarioEntity>
}

interface InventarioItemRepository : JpaRepository<InventarioItemEntity, Long> {

    /**
     * Etiquetas de [tags] encontradas em inventários FINALIZADOS de OUTRO local, finalizados entre [desde] e [ate]:
     * [tag, nome do local, finalizadoEm]. O esperado de um local tira essas (o bem foi achado depois em outro lugar).
     */
    @Query(
        """select it.tag, l.nome, i.finalizadoEm from InventarioItemEntity it, InventarioEntity i, LocalEntity l
           where i.id = it.inventarioId and l.id = i.localId and i.status = org.example.model.StatusInventario.FINALIZADO
             and i.localId <> :localId and i.finalizadoEm > :desde and i.finalizadoEm < :ate and it.tag in :tags"""
    )
    fun encontradasEmOutroLocal(@Param("tags") tags: Collection<String>, @Param("localId") localId: Long,
                                @Param("desde") desde: java.time.LocalDateTime, @Param("ate") ate: java.time.LocalDateTime): List<Array<Any?>>

    /** Último inventário FINALIZADO em que a etiqueta foi encontrada: [nome do local, finalizadoEm] (ficha do bem). */
    @Query(
        """select l.nome, i.finalizadoEm from InventarioItemEntity it, InventarioEntity i, LocalEntity l
           where i.id = it.inventarioId and l.id = i.localId and i.status = org.example.model.StatusInventario.FINALIZADO
             and it.tag = :tag order by i.finalizadoEm desc"""
    )
    fun ultimoInventarioDaTag(@Param("tag") tag: String, pagina: org.springframework.data.domain.Pageable): List<Array<Any?>>

    fun findByInventarioIdOrderByLidoEmAsc(inventarioId: String): List<InventarioItemEntity>

    @Query("select i.tag from InventarioItemEntity i where i.inventarioId = :inventarioId")
    fun tagsDoInventario(inventarioId: String): List<String>

    /** Envio de etiquetas: quais destas já estão no inventário (só as do envio, não todas as do inventário). */
    @Query("select i.tag from InventarioItemEntity i where i.inventarioId = :inventarioId and i.tag in :tags")
    fun tagsJaNoInventario(@Param("inventarioId") inventarioId: String, @Param("tags") tags: Collection<String>): List<String>

    fun countByInventarioId(inventarioId: String): Long

    /** [inventarioId, total de itens, itens cadastrados] só dos inventários da página (antes somava todos). */
    @Query(
        """select i.inventarioId, count(i), sum(case when i.cadastrada = true then 1 else 0 end)
           from InventarioItemEntity i where i.inventarioId in :ids group by i.inventarioId"""
    )
    fun contarPorInventario(ids: Collection<String>): List<Array<Any?>>
}
