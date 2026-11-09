package org.example.model

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime

/** Guia de transferência de bens (tabela transferencias, V24). O número da guia é o id. */
@Entity
@Table(name = "transferencias")
class TransferenciaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val data: LocalDate,

    val localDestinoId: Long? = null,
    /** Nome do local de destino no momento ("Matriz › Sala 101"). */
    val localDestino: String? = null,

    val responsavelDestinoId: Long? = null,
    /** "Maria Souza (TI, mat. 123)" no momento. */
    val responsavelDestino: String? = null,

    val solicitante: String? = null,
    val motivo: String? = null,

    /** Saiu das divergências deste inventário (1.6.1). */
    val inventarioId: String? = null,

    @Column(nullable = false)
    val registradaPor: String,

    @Column(nullable = false)
    val criadaEm: LocalDateTime = LocalDateTime.now()
)

/** Um bem da guia, com a origem dele no momento da transferência (tabela transferencia_itens, V24). */
@Entity
@Table(name = "transferencia_itens")
class TransferenciaItemEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val transferenciaId: Long,

    val tagId: Long? = null,

    @Column(nullable = false)
    val tagReal: String,

    val patrimonio: String? = null,
    val descricao: String? = null,
    val localAnterior: String? = null,
    val responsavelAnterior: String? = null
)
