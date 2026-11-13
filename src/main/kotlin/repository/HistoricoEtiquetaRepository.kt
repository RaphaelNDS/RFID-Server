package org.example.repository

import org.example.model.HistoricoEtiquetaEntity
import org.springframework.data.jpa.repository.JpaRepository

/** Somente leitura e inclusão: nenhuma tela altera ou apaga o histórico. */
interface HistoricoEtiquetaRepository : JpaRepository<HistoricoEtiquetaEntity, Long> {

    /** Histórico de uma etiqueta (inclusive excluída), do mais recente para o mais antigo. */
    fun findByTagRealOrderByMomentoDescIdDesc(tagReal: String): List<HistoricoEtiquetaEntity>

    /** As últimas mudanças em todas as etiquetas (tela de histórico sem filtro). */
    fun findTop200ByOrderByMomentoDescIdDesc(): List<HistoricoEtiquetaEntity>

    /** 1.21.0: quando cada etiqueta foi cadastrada (o primeiro CADASTRO), para a revisão automática. */
    @org.springframework.data.jpa.repository.Query(
        "select h.tagReal, min(h.momento) from HistoricoEtiquetaEntity h where h.acao = org.example.model.AcaoHistorico.CADASTRO and h.tagReal in :reais group by h.tagReal"
    )
    fun cadastros(@org.springframework.data.repository.query.Param("reais") reais: Collection<String>): List<Array<Any>>
}
