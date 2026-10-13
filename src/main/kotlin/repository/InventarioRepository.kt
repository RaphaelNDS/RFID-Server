package org.example.repository

import org.example.model.InventarioEntity
import org.example.model.InventarioItemEntity
import org.example.model.LocalEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional

interface LocalRepository : JpaRepository<LocalEntity, Long> {

    fun findByNomeIgnoreCase(nome: String): Optional<LocalEntity>

    fun findAllByOrderByNomeAsc(): List<LocalEntity>
}

interface InventarioRepository : JpaRepository<InventarioEntity, String> {

    fun findAllByOrderByIniciadoEmDesc(): List<InventarioEntity>

    fun findByLocalIdOrderByIniciadoEmDesc(localId: Long): List<InventarioEntity>
}

interface InventarioItemRepository : JpaRepository<InventarioItemEntity, Long> {

    fun findByInventarioIdOrderByLidoEmAsc(inventarioId: String): List<InventarioItemEntity>

    @Query("select i.tag from InventarioItemEntity i where i.inventarioId = :inventarioId")
    fun tagsDoInventario(inventarioId: String): List<String>

    /** [inventarioId, total de itens, itens cadastrados] por inventário. */
    @Query(
        """select i.inventarioId, count(i), sum(case when i.cadastrada = true then 1 else 0 end)
           from InventarioItemEntity i group by i.inventarioId"""
    )
    fun contarPorInventario(): List<Array<Any?>>
}
