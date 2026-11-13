package org.example.repository

import org.example.model.InventarioCiclicoEntity
import org.springframework.data.jpa.repository.JpaRepository

interface InventarioCiclicoRepository : JpaRepository<InventarioCiclicoEntity, Int>
