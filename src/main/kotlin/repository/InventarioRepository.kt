package org.example.repository

import org.example.model.InventarioEntity
import org.example.model.InventarioItemEntity
import org.example.model.LocalEntity
import org.example.model.StatusInventario
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional

interface LocalRepository : JpaRepository<LocalEntity, Long> {

    fun findByNomeIgnoreCase(nome: String): Optional<LocalEntity>

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

interface InventarioItemRepository : JpaRepository<InventarioItemEntity, Long> {

    fun findByInventarioIdOrderByLidoEmAsc(inventarioId: String): List<InventarioItemEntity>

    @Query("select i.tag from InventarioItemEntity i where i.inventarioId = :inventarioId")
    fun tagsDoInventario(inventarioId: String): List<String>

    /**
     * Relatório "não vistas": [tag, hora, local] do inventário mais recente em que cada tag foi lida.
     * DISTINCT ON é do PostgreSQL (o banco do servidor e dos testes): uma linha por tag, a de maior lido_em.
     */
    @Query(
        value = """select distinct on (it.tag) it.tag, it.lido_em, l.nome
                   from inventario_itens it
                   join inventarios inv on inv.id = it.inventario_id
                   join locais l on l.id = inv.local_id
                   order by it.tag, it.lido_em desc""",
        nativeQuery = true
    )
    fun ultimoInventarioPorTag(): List<Array<Any?>>

    /** [inventarioId, total de itens, itens cadastrados] só dos inventários da página (antes somava todos). */
    @Query(
        """select i.inventarioId, count(i), sum(case when i.cadastrada = true then 1 else 0 end)
           from InventarioItemEntity i where i.inventarioId in :ids group by i.inventarioId"""
    )
    fun contarPorInventario(ids: Collection<String>): List<Array<Any?>>
}
