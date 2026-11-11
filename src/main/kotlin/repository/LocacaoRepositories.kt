package org.example.repository

import org.example.model.ClienteEntity
import org.example.model.PedidoItemEntity
import org.example.model.PedidoLinhaEntity
import org.example.model.PedidoLocacaoEntity
import org.example.model.SituacaoPedido
import org.example.model.VendaEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface ClienteRepository : JpaRepository<ClienteEntity, Long> {
    fun findAllByOrderByAtivoDescNomeAsc(): List<ClienteEntity>
    fun findFirstByDocumento(documento: String): ClienteEntity?
}

interface PedidoLocacaoRepository : JpaRepository<PedidoLocacaoEntity, Long> {
    /**
     * Mais novos primeiro. [situacoes] vazio = todas. [atrasados]: com equipamento na rua e a devolução prevista antes de
     * [hoje]. [busca]: número exato, parte do nome do cliente, ou etiqueta/patrimônio de um equipamento do pedido.
     */
    @Query(
        value = """select p from PedidoLocacaoEntity p where
                   (:todas = true or p.situacao in :situacoes)
                   and (:atrasados = false or (p.previsaoDevolucao < :hoje and exists (select 1 from PedidoItemEntity a
                        where a.pedidoId = p.id and a.devolvidoEm is null)))
                   and (:busca = '' or cast(p.id as string) = :busca or lower(p.cliente) like lower(concat('%', :busca, '%'))
                        or exists (select 1 from PedidoItemEntity i where i.pedidoId = p.id and (i.tagReal = upper(:busca) or lower(i.patrimonio) = lower(:busca))))
                   order by p.id desc"""
    )
    fun buscar(
        @Param("todas") todas: Boolean, @Param("situacoes") situacoes: Collection<SituacaoPedido>, @Param("atrasados") atrasados: Boolean,
        @Param("hoje") hoje: LocalDate, @Param("busca") busca: String, pagina: Pageable
    ): Page<PedidoLocacaoEntity>

    @Query("""select count(p) from PedidoLocacaoEntity p where p.previsaoDevolucao < :hoje
              and exists (select 1 from PedidoItemEntity a where a.pedidoId = p.id and a.devolvidoEm is null)""")
    fun contarAtrasados(@Param("hoje") hoje: LocalDate): Long

    /** Para o coletor: os pedidos nestas situações, pela saída prevista (sem data por último) e o número. */
    @Query("select p from PedidoLocacaoEntity p where p.situacao in :situacoes order by coalesce(p.saidaPrevista, p.previsaoDevolucao), p.id")
    fun paraColetor(@Param("situacoes") situacoes: Collection<SituacaoPedido>): List<PedidoLocacaoEntity>

    fun findAllByClienteIdOrderByIdDesc(clienteId: Long): List<PedidoLocacaoEntity>
}

interface PedidoLinhaRepository : JpaRepository<PedidoLinhaEntity, Long> {
    fun findAllByPedidoIdOrderByIdAsc(pedidoId: Long): List<PedidoLinhaEntity>
    fun findAllByPedidoIdIn(ids: Collection<Long>): List<PedidoLinhaEntity>
}

interface PedidoItemRepository : JpaRepository<PedidoItemEntity, Long> {
    fun findAllByPedidoIdOrderByIdAsc(pedidoId: Long): List<PedidoItemEntity>
    fun findAllByPedidoIdIn(ids: Collection<Long>): List<PedidoItemEntity>

    /** O item em aberto do equipamento (saiu e a devolução ainda não foi conferida): no máximo um (índice único). */
    fun findFirstByTagIdAndConferidoEmIsNull(tagId: Long): PedidoItemEntity?

    fun findAllByTagIdInAndConferidoEmIsNull(tagIds: Collection<Long>): List<PedidoItemEntity>

    /** Devolvidos que esperam a conferência, mais antigos primeiro. */
    fun findAllByDevolvidoEmIsNotNullAndConferidoEmIsNullOrderByDevolvidoEmAsc(): List<PedidoItemEntity>

    fun countByDevolvidoEmIsNotNullAndConferidoEmIsNull(): Long

    /** Linha do tempo do equipamento (1.16.0): todas as saídas dele, pela etiqueta (continua depois de excluído). */
    fun findAllByTagRealOrderBySaidaEmDesc(tagReal: String): List<PedidoItemEntity>
}

interface VendaRepository : JpaRepository<VendaEntity, Long> {
    fun findFirstByTagIdAndDesfeitaEmIsNullOrderByIdDesc(tagId: Long): VendaEntity?

    /** Linha do tempo do equipamento (1.16.0). */
    fun findAllByTagRealOrderByIdDesc(tagReal: String): List<VendaEntity>
}

/** Prorrogações da devolução prevista de um pedido (1.16.0). */
interface PedidoProrrogacaoRepository : JpaRepository<org.example.model.PedidoProrrogacaoEntity, Long> {
    fun findAllByPedidoIdOrderByIdAsc(pedidoId: Long): List<org.example.model.PedidoProrrogacaoEntity>
}
