package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/** Revisão automática (1.21.0, tabela regras_revisao, V35): uma regra por tipo de equipamento. */
@Entity
@Table(name = "regras_revisao")
class RegraRevisaoEntity(
    @Id
    val tipoEquipamentoId: Long,

    /** Revisar depois de tantas locações (saídas) desde a última revisão. */
    var aCadaLocacoes: Int? = null,

    /** Revisar depois de tantos dias desde a última revisão (ou do cadastro). */
    var aCadaDias: Int? = null,

    @Column(nullable = false)
    var alteradaPor: String,

    @Column(nullable = false)
    var alteradaEm: LocalDateTime = LocalDateTime.now()
) {
    /** "a cada 5 locações ou 90 dias". */
    val descricao: String get() = "a cada " + listOfNotNull(aCadaLocacoes?.let { "$it locação(ões)" }, aCadaDias?.let { "$it dias" }).joinToString(" ou ")
}
