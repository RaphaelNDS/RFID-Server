package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Sessão de um operador logado num coletor (12h). Só o hash do token fica no banco.
 * Uma ação feita no coletor vale se aconteceu entre [criadaEm] e o fim da sessão
 * (o que vier antes: [expiraEm] ou [encerradaEm]), mesmo que chegue ao servidor depois.
 */
@Entity
@Table(name = "sessoes_operador")
class SessaoOperadorEntity(

    @Id
    @Column(length = 64)
    val tokenHash: String,

    @Column(name = "usuario_id", nullable = false)
    val usuarioId: Long,

    @Column(nullable = false, length = 100)
    val dispositivo: String,

    @Column(nullable = false)
    val criadaEm: LocalDateTime,

    @Column(nullable = false)
    val expiraEm: LocalDateTime,

    var encerradaEm: LocalDateTime? = null
) {
    /** Fim real da sessão: logout ou expiração, o que veio primeiro. */
    val fim: LocalDateTime get() = encerradaEm?.takeIf { it.isBefore(expiraEm) } ?: expiraEm
}
