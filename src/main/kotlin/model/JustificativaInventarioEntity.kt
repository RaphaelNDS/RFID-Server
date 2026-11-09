package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/** Por que um bem esperado não foi encontrado no inventário (marcado no coletor na finalização). */
enum class MotivoFalta(val rotulo: String) {
    EMPRESTADO("Emprestado"),
    MANUTENCAO("Em manutenção"),
    BAIXADO("Baixado"),
    NAO_ENCONTRADO("Não encontrado"),
    OUTRO("Outro");

    companion object {
        /** Pelo código (EMPRESTADO) ou pelo nome ("Emprestado", "Em manutenção"), sem diferenciar maiúsculas nem acento. */
        fun de(texto: String?): MotivoFalta? {
            val limpo = semAcento(texto?.trim().orEmpty()).uppercase().replace(' ', '_')
            if (limpo.isEmpty()) return null
            return entries.firstOrNull { it.name == limpo || semAcento(it.rotulo).uppercase().replace(' ', '_') == limpo }
        }

        private fun semAcento(s: String) =
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    }
}

/**
 * Justificativa de um bem que faltou no inventário (tabela inventario_justificativas, V22). Uma por etiqueta e
 * inventário: o reenvio do coletor atualiza a mesma.
 */
@Entity
@Table(name = "inventario_justificativas")
class JustificativaInventarioEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "inventario_id", nullable = false, length = 36)
    val inventarioId: String,

    @Column(nullable = false)
    val tag: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var motivo: MotivoFalta,

    @Column(length = 500)
    var observacao: String? = null,

    var operador: String? = null,

    @Column(nullable = false)
    var registradaEm: LocalDateTime
)
