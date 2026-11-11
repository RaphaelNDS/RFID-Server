package org.example.repository

import org.example.model.ManutencaoEntity
import org.example.model.StatusManutencao
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface ManutencaoRepository : JpaRepository<ManutencaoEntity, Long> {
    /**
     * Mais novas primeiro. [filtro]: "" (todas), "ABERTAS", "ATRASADAS" (abertas com a previsão de retorno vencida
     * antes de [hoje]) ou "ENCERRADAS" (concluídas ou canceladas). [busca]: etiqueta ou patrimônio exatos, ou parte
     * do fornecedor.
     */
    @Query(
        value = """select m from ManutencaoEntity m where
                   (:busca = '' or m.tagReal = upper(:busca) or lower(m.patrimonio) = lower(:busca)
                      or lower(coalesce(m.fornecedor, '')) like lower(concat('%', :busca, '%')))
                   and (:filtro = ''
                     or (:filtro = 'ABERTAS' and m.status = :aberta)
                     or (:filtro = 'ATRASADAS' and m.status = :aberta and m.previsaoRetorno < :hoje)
                     or (:filtro = 'ENCERRADAS' and m.status <> :aberta))
                   order by m.dataEnvio desc, m.id desc"""
    )
    fun buscar(
        @Param("busca") busca: String, @Param("filtro") filtro: String, @Param("hoje") hoje: LocalDate,
        @Param("aberta") aberta: StatusManutencao, pagina: Pageable
    ): Page<ManutencaoEntity>

    @Query("select count(m) from ManutencaoEntity m where m.status = :aberta and m.previsaoRetorno < :hoje")
    fun contarAtrasadas(@Param("hoje") hoje: LocalDate, @Param("aberta") aberta: StatusManutencao): Long

    fun findFirstByTagIdAndStatus(tagId: Long, status: StatusManutencao): ManutencaoEntity?

    /** Painel do dia (1.13.0): as abertas, mais antigas primeiro. */
    fun findAllByStatusOrderByDataEnvioAsc(status: StatusManutencao): List<ManutencaoEntity>

    fun findAllByTagIdOrderByDataEnvioDescIdDesc(tagId: Long): List<ManutencaoEntity>
}
