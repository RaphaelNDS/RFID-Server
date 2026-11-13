package org.example.repository

import org.example.model.MovimentoEntity
import org.example.model.MovimentoItemEntity
import org.example.model.TipoMovimento
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface MovimentoRepository : JpaRepository<MovimentoEntity, Long> {

    /**
     * Tela Movimentações: os filtros vazios valem tudo. [padrao] ("" = todos; senão "%termo%" em minúsculas, com % e _
     * escapados) procura no EPC, no patrimônio e na descrição dos equipamentos, no destino e na observação.
     */
    @Query(
        """select m from MovimentoEntity m
           where m.tipo in :tipos
             and m.dataHora >= :de and m.dataHora < :ate
             and (:padrao = ''
                  or lower(coalesce(m.destino, '')) like :padrao escape '\'
                  or lower(coalesce(m.observacao, '')) like :padrao escape '\'
                  or exists (select 1 from MovimentoItemEntity i where i.movimentoId = m.id
                             and (lower(i.tagReal) like :padrao escape '\'
                                  or lower(coalesce(i.patrimonio, '')) like :padrao escape '\'
                                  or lower(coalesce(i.descricao, '')) like :padrao escape '\')))"""
    )
    fun buscar(
        @Param("tipos") tipos: Collection<TipoMovimento>,
        @Param("de") de: LocalDateTime,
        @Param("ate") ate: LocalDateTime,
        @Param("padrao") padrao: String,
        pagina: Pageable
    ): Page<MovimentoEntity>

    /** Tela Início: as últimas movimentações. */
    fun findTop5ByOrderByDataHoraDescIdDesc(): List<MovimentoEntity>
}

interface MovimentoItemRepository : JpaRepository<MovimentoItemEntity, Long> {

    fun findAllByMovimentoIdOrderByIdAsc(movimentoId: Long): List<MovimentoItemEntity>

    fun findAllByMovimentoIdIn(movimentoIds: Collection<Long>): List<MovimentoItemEntity>

    /** A movimentação mais recente do equipamento de um tipo (a saída que o levou, para "Fora do estoque desde"). */
    @Query(
        """select m from MovimentoEntity m where m.tipo = :tipo
           and exists (select 1 from MovimentoItemEntity i where i.movimentoId = m.id and i.tagId = :tagId)
           order by m.dataHora desc, m.id desc"""
    )
    fun ultimosDoTipo(@Param("tagId") tagId: Long, @Param("tipo") tipo: TipoMovimento, pagina: Pageable): List<MovimentoEntity>
}
