package org.example.repository

import org.example.model.EntregaFotoEntity
import org.example.model.EntregaPedidoEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface EntregaPedidoRepository : JpaRepository<EntregaPedidoEntity, Long> {
    fun findAllByPedidoIdOrderByIdAsc(pedidoId: Long): List<EntregaPedidoEntity>
}

interface EntregaFotoRepository : JpaRepository<EntregaFotoEntity, Long> {
    /** Só os ids das fotos (sem carregar os bytes), por entrega. */
    @Query("select f.id, f.entregaId from EntregaFotoEntity f where f.entregaId in :entregas order by f.id")
    fun idsPorEntrega(@Param("entregas") entregas: Collection<Long>): List<Array<Any>>

    fun findAllByEntregaIdOrderByIdAsc(entregaId: Long): List<EntregaFotoEntity>
}
