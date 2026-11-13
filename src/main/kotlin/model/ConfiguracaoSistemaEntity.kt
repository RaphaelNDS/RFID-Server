package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/** Configuração do sistema em chave e valor (1.30.0, tabela configuracoes_sistema, V41). */
@Entity
@Table(name = "configuracoes_sistema")
class ConfiguracaoSistemaEntity(
    @Id
    @Column(length = 80)
    val chave: String,

    @Column(nullable = false, length = 500)
    var valor: String,

    var alteradoPor: String? = null,

    var alteradoEm: LocalDateTime? = null
)
