package org.example.repository

import org.example.model.AreaAuditoria
import org.example.model.AuditoriaEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AuditoriaRepository : JpaRepository<AuditoriaEntity, Long> {

    /**
     * Tela de auditoria: uma página, da [area] escolhida (null = todas) e com busca ([padrao] vazio = tudo; senão
     * "%termo%" em minúsculas, com % e _ escapados) em quem fez, ação, alvo e detalhe.
     */
    @Query(
        """select a from AuditoriaEntity a
           where (:area is null or a.area = :area)
             and (:padrao = ''
                  or lower(a.usuario) like :padrao escape '\'
                  or lower(a.acao) like :padrao escape '\'
                  or lower(coalesce(a.alvo, '')) like :padrao escape '\'
                  or lower(coalesce(a.detalhe, '')) like :padrao escape '\')"""
    )
    fun buscar(@Param("area") area: AreaAuditoria?, @Param("padrao") padrao: String, pagina: Pageable): Page<AuditoriaEntity>
}
