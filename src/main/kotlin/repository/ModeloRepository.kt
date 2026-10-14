package org.example.repository

import org.example.model.ModeloEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ModeloRepository : JpaRepository<ModeloEntity, Long> {

    fun findByMarcaId(marcaId: Long): List<ModeloEntity>

    fun findByNomeIgnoreCaseAndMarcaId(nome: String, marcaId: Long): Optional<ModeloEntity>

    fun existsByMarcaId(marcaId: Long): Boolean

    fun countByMarcaTipoId(tipoId: Long): Long
}
