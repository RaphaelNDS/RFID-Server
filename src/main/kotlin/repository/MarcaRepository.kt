package org.example.repository

import org.example.model.MarcaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface MarcaRepository : JpaRepository<MarcaEntity, Long> {

    fun findByTipoId(tipoId: Long): List<MarcaEntity>

    fun findByNomeIgnoreCaseAndTipoId(nome: String, tipoId: Long): Optional<MarcaEntity>

    fun existsByTipoId(tipoId: Long): Boolean
}
