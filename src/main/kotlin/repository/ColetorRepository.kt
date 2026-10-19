package org.example.repository

import org.example.model.ColetorEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ColetorRepository : JpaRepository<ColetorEntity, String>
