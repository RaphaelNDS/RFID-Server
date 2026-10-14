package org.example.repository

import org.example.model.VersaoAppEntity
import org.springframework.data.jpa.repository.JpaRepository

interface VersaoAppRepository : JpaRepository<VersaoAppEntity, Long> {

    /** Todas as versões, da mais nova para a mais antiga (histórico da tela). */
    fun findAllByOrderByCodigoDesc(): List<VersaoAppEntity>

    /** A versão mais nova já publicada (inclusive com o arquivo removido): a próxima precisa ter código maior. */
    fun findFirstByOrderByCodigoDesc(): VersaoAppEntity?

    /** A versão que os coletores devem instalar: a mais nova que ainda tem o arquivo. */
    fun findFirstByArquivoRemovidoFalseOrderByCodigoDesc(): VersaoAppEntity?
}
