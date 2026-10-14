package org.example.repository

import org.example.model.TipoEquipamentoEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface TipoEquipamentoRepository : JpaRepository<TipoEquipamentoEntity, Long> {

    fun findByNomeIgnoreCase(nome: String): Optional<TipoEquipamentoEntity>
}
