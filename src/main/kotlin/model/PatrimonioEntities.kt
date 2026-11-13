package org.example.model

import jakarta.persistence.*
import java.text.Normalizer
import java.time.LocalDateTime

/**
 * Situação do equipamento (tags.situacao). 2.0.0 (V34): só quatro. DISPONIVEL e EXTRAVIADO mudam pela edição da
 * etiqueta ([EDITAVEIS]); FORA vem da saída (Movimentações) e volta para DISPONIVEL pela entrada; BAIXADO vem da baixa.
 */
enum class SituacaoBem(val rotulo: String) {
    DISPONIVEL("No estoque"),
    FORA("Fora do estoque"),
    EXTRAVIADO("Extraviado"),
    BAIXADO("Baixado");

    companion object {
        /** As que a edição da etiqueta (e a importação) pode escolher. */
        val EDITAVEIS = listOf(DISPONIVEL, EXTRAVIADO)

        /** Fora do acervo: não aparecem nas listas por padrão nem no esperado dos inventários. */
        val FORA_DO_ACERVO = setOf(BAIXADO)

        /** Fora do esperado do inventário: além dos baixados, o que saiu do estoque. */
        val FORA_DO_ESPERADO = FORA_DO_ACERVO + FORA

        /** O que a lista de etiquetas mostra sem filtro. */
        val NO_ACERVO = entries.filter { it !in FORA_DO_ACERVO }

        /**
         * Nomes de versões anteriores (planilhas antigas, app antigo): viram os de agora, como a V34 fez no banco.
         * O que estava com cliente, em devolução ou em manutenção está fora do estoque; à venda continua no estoque.
         */
        private val ANTIGOS = mapOf(
            "EM_USO" to DISPONIVEL, "EM_ESTOQUE" to DISPONIVEL, "A_VENDA" to DISPONIVEL,
            "EMPRESTADO" to FORA, "ALUGADO" to FORA, "EM_DEVOLUCAO" to FORA, "EM_MANUTENCAO" to FORA,
            "VENDIDO" to BAIXADO
        )

        /** Pelo código ou pelo nome ("No estoque"), sem diferenciar maiúsculas nem acento (importação de planilha). */
        fun de(texto: String?): SituacaoBem? = porCodigoOuNome(texto, entries) { it.rotulo } ?: texto?.let {
            ANTIGOS[Normalizer.normalize(it.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                .uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')]
        }
    }
}

/** Por que o bem foi baixado. */
enum class MotivoBaixa(val rotulo: String) {
    VENDA("Venda"),
    DOACAO("Doação"),
    SUCATA("Sucata / descarte"),
    FURTO_ROUBO("Furto ou roubo"),
    PERDA("Perda"),
    OUTRO("Outro");

    companion object {
        fun de(texto: String?): MotivoBaixa? = porCodigoOuNome(texto, entries) { it.rotulo }
    }
}

/** Código (DISPONIVEL) ou nome ("Disponível"), sem diferenciar maiúsculas, acento, espaço ou "/". */
internal fun <E : Enum<E>> porCodigoOuNome(texto: String?, valores: List<E>, rotulo: (E) -> String): E? {
    fun limpar(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
    val alvo = limpar(texto?.trim().orEmpty())
    if (alvo.isEmpty()) return null
    return valores.firstOrNull { it.name == alvo || limpar(rotulo(it)) == alvo }
}

/** Unidade da empresa (ex.: Matriz, Filial SP), que agrupa os locais (tabela unidades, V23). */
@Entity
@Table(name = "unidades")
class UnidadeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 120)
    var nome: String,

    @Column(nullable = false)
    var ativa: Boolean = true,

    @Column(nullable = false)
    val criadaEm: LocalDateTime = LocalDateTime.now()
)
