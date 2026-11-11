package org.example.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

enum class TipoManutencao(val rotulo: String) {
    CORRETIVA("Corretiva (conserto)"),
    PREVENTIVA("Preventiva (revisão)")
}

enum class StatusManutencao(val rotulo: String) {
    ABERTA("Aberta"),
    CONCLUIDA("Concluída"),
    CANCELADA("Cancelada")
}

enum class ResultadoManutencao(val rotulo: String) {
    CONSERTADO("Consertado / revisado"),
    SEM_CONSERTO("Sem conserto")
}

/** Ordem de manutenção de um bem (tabela manutencoes, V28). O número da ordem é o id. */
@Entity
@Table(name = "manutencoes")
class ManutencaoEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val tagId: Long? = null,

    @Column(nullable = false)
    val tagReal: String,

    val patrimonio: String? = null,

    /** "Notebook Dell Latitude" no momento. */
    @Column(name = "descricao_bem")
    val descricaoBem: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val tipo: TipoManutencao,

    @Column(nullable = false, length = 500)
    val problema: String,

    @Column(length = 160)
    val fornecedor: String? = null,

    @Column(nullable = false)
    val dataEnvio: LocalDate,

    val previsaoRetorno: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val situacaoAnterior: SituacaoBem,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: StatusManutencao = StatusManutencao.ABERTA,

    var concluidaEm: LocalDate? = null,

    @Column(length = 500)
    var servicoRealizado: String? = null,

    @Column(precision = 14, scale = 2)
    var custo: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var resultado: ResultadoManutencao? = null,

    @Column(nullable = false)
    val registradaPor: String,

    var concluidaPor: String? = null,

    @Column(nullable = false)
    val criadaEm: LocalDateTime = LocalDateTime.now()
) {
    val aberta: Boolean get() = status == StatusManutencao.ABERTA
}
