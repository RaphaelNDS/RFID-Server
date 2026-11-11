package org.example.model

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime

/** Empréstimo de bens (tabela emprestimos, V27). O número do empréstimo é o id. */
@Entity
@Table(name = "emprestimos")
class EmprestimoEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val responsavelId: Long,

    /** "Maria Souza (TI, mat. 123)" no momento do empréstimo. */
    @Column(nullable = false)
    val responsavel: String,

    @Column(nullable = false)
    val dataSaida: LocalDate,

    @Column(nullable = false)
    val previsaoDevolucao: LocalDate,

    val finalidade: String? = null,

    @Column(nullable = false)
    val registradoPor: String,

    @Column(nullable = false)
    val criadoEm: LocalDateTime = LocalDateTime.now()
)

/** Um bem do empréstimo, com a situação de antes (para onde volta na devolução). Tabela emprestimo_itens, V27. */
@Entity
@Table(name = "emprestimo_itens")
class EmprestimoItemEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val emprestimoId: Long,

    val tagId: Long? = null,

    @Column(nullable = false)
    val tagReal: String,

    val patrimonio: String? = null,
    val descricao: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val situacaoAnterior: SituacaoBem,

    var devolvidoEm: LocalDate? = null,
    var devolvidoPor: String? = null,

    @Column(length = 500)
    var condicaoDevolucao: String? = null
) {
    val devolvido: Boolean get() = devolvidoEm != null
}
