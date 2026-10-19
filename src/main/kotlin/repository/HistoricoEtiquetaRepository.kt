package org.example.repository

import org.example.model.HistoricoEtiquetaEntity
import org.springframework.data.jpa.repository.JpaRepository

/** Somente leitura e inclusão: nenhuma tela altera ou apaga o histórico. */
interface HistoricoEtiquetaRepository : JpaRepository<HistoricoEtiquetaEntity, Long> {

    /** Histórico de uma etiqueta (inclusive excluída), do mais recente para o mais antigo. */
    fun findByTagRealOrderByMomentoDescIdDesc(tagReal: String): List<HistoricoEtiquetaEntity>

    /** As últimas mudanças em todas as etiquetas (tela de histórico sem filtro). */
    fun findTop200ByOrderByMomentoDescIdDesc(): List<HistoricoEtiquetaEntity>
}
