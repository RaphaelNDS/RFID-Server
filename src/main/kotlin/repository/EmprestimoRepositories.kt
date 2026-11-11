package org.example.repository

import org.example.model.EmprestimoEntity
import org.example.model.EmprestimoItemEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface EmprestimoRepository : JpaRepository<EmprestimoEntity, Long> {
    /**
     * Mais novos primeiro. [situacao]: "" (todos), "ABERTOS" (algum bem não voltou), "ATRASADOS" (abertos com o prazo
     * vencido antes de [hoje]) ou "DEVOLVIDOS" (todos os bens voltaram). [busca]: etiqueta ou patrimônio exatos, ou
     * parte do nome de quem recebeu.
     */
    @Query(
        value = """select e from EmprestimoEntity e where
                   (:busca = '' or lower(e.responsavel) like lower(concat('%', :busca, '%')) or exists (select 1 from EmprestimoItemEntity i
                      where i.emprestimoId = e.id and (i.tagReal = upper(:busca) or lower(i.patrimonio) = lower(:busca))))
                   and (:situacao = ''
                     or (:situacao = 'ABERTOS' and exists (select 1 from EmprestimoItemEntity a where a.emprestimoId = e.id and a.devolvidoEm is null))
                     or (:situacao = 'ATRASADOS' and e.previsaoDevolucao < :hoje and exists (select 1 from EmprestimoItemEntity a where a.emprestimoId = e.id and a.devolvidoEm is null))
                     or (:situacao = 'DEVOLVIDOS' and not exists (select 1 from EmprestimoItemEntity a where a.emprestimoId = e.id and a.devolvidoEm is null)))
                   order by e.dataSaida desc, e.id desc"""
    )
    fun buscar(@Param("busca") busca: String, @Param("situacao") situacao: String, @Param("hoje") hoje: LocalDate, pagina: Pageable): Page<EmprestimoEntity>

    /** Quantos empréstimos abertos estão com o prazo vencido. */
    @Query("""select count(e) from EmprestimoEntity e where e.previsaoDevolucao < :hoje
              and exists (select 1 from EmprestimoItemEntity a where a.emprestimoId = e.id and a.devolvidoEm is null)""")
    fun contarAtrasados(@Param("hoje") hoje: LocalDate): Long
}

interface EmprestimoItemRepository : JpaRepository<EmprestimoItemEntity, Long> {
    fun findAllByEmprestimoIdOrderByIdAsc(emprestimoId: Long): List<EmprestimoItemEntity>

    fun findAllByEmprestimoIdIn(ids: Collection<Long>): List<EmprestimoItemEntity>

    /** O empréstimo aberto do bem (no máximo um: índice único ux_emprestimo_itens_aberto). */
    fun findFirstByTagIdAndDevolvidoEmIsNull(tagId: Long): EmprestimoItemEntity?

    fun findAllByTagIdInAndDevolvidoEmIsNull(tagIds: Collection<Long>): List<EmprestimoItemEntity>
}
