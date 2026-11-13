package org.example.repository

import org.example.model.ConfiguracaoSistemaEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ConfiguracaoSistemaRepository : JpaRepository<ConfiguracaoSistemaEntity, String>
