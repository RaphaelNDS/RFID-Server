package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/** Saída (o equipamento sai do estoque e fica "Fora do estoque") ou entrada (volta para "No estoque"). */
enum class TipoMovimento(val rotulo: String) {
    SAIDA("Saída"),
    ENTRADA("Entrada");

    companion object {
        /** "SAIDA", "saída", "Entrada"... sem diferenciar maiúsculas nem acento; null = nenhum dos dois. */
        fun de(texto: String?): TipoMovimento? = porCodigoOuNome(texto, entries) { it.rotulo }
    }
}

/** Por onde a movimentação foi registrada. */
enum class OrigemMovimento(val rotulo: String) {
    WEB("Web"),
    COLETOR("Coletor")
}

/**
 * Uma movimentação (2.0.0, V34): saída ou entrada de um ou mais equipamentos, com número (o id). O destino é texto
 * livre, obrigatório na saída (para onde o equipamento foi: cliente, obra, filial...).
 */
@Entity
@Table(name = "movimentos")
class MovimentoEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val tipo: TipoMovimento,

    @Column(name = "data_hora", nullable = false)
    val dataHora: LocalDateTime,

    @Column(length = 255)
    val destino: String? = null,

    @Column(length = 500)
    val observacao: String? = null,

    /** Na web, o email de quem registrou; no coletor, o nome do operador e o coletor. */
    @Column(name = "registrado_por", nullable = false)
    val registradoPor: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val origem: OrigemMovimento,

    @Column(name = "criado_em", nullable = false)
    val criadoEm: LocalDateTime = LocalDateTime.now()
)

/**
 * Um equipamento da movimentação, com o EPC, o patrimônio e a descrição no momento (o comprovante continua legível se
 * a etiqueta for excluída depois: tag_id vira nulo).
 */
@Entity
@Table(name = "movimento_itens")
class MovimentoItemEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "movimento_id", nullable = false)
    val movimentoId: Long,

    @Column(name = "tag_id")
    val tagId: Long? = null,

    @Column(name = "tag_real", nullable = false)
    val tagReal: String,

    val patrimonio: String? = null,

    val descricao: String? = null
)
