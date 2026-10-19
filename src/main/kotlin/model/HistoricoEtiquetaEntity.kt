package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

enum class AcaoHistorico(val rotulo: String) {
    CADASTRO("Cadastrou"),
    ALTERACAO("Alterou"),
    EXCLUSAO("Excluiu")
}

/**
 * Uma linha do histórico de uma etiqueta (HistoricoEtiquetaService). No cadastro e na exclusão, [valorNovo] /
 * [valorAntigo] trazem um retrato de todos os campos; na alteração, uma linha por [campo] que mudou.
 */
@Entity
@Table(name = "historico_etiquetas")
class HistoricoEtiquetaEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /** Id da etiqueta quando aconteceu (sem chave estrangeira: a etiqueta pode ter sido excluída). */
    val tagId: Long?,

    @Column(nullable = false)
    val tagReal: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val acao: AcaoHistorico,

    @Column(length = 50)
    val campo: String? = null,

    @Column(length = 500)
    val valorAntigo: String? = null,

    @Column(length = 500)
    val valorNovo: String? = null,

    /** Email do usuário (web) ou nome do operador (coletor). */
    @Column(nullable = false)
    val usuario: String,

    /** "Web" ou "Coletor coletor-01". */
    @Column(nullable = false, length = 120)
    val origem: String,

    @Column(nullable = false)
    val momento: LocalDateTime
)
