package org.example.model

import jakarta.persistence.*
import java.text.Normalizer
import java.time.LocalDateTime

/**
 * Situação do equipamento (tags.situacao, V23; locadora desde a 1.12.0, V29). Só as [EDITAVEIS] mudam pela edição da
 * etiqueta: ALUGADO e EM_DEVOLUCAO vêm dos pedidos de locação, VENDIDO da venda e BAIXADO da baixa.
 */
enum class SituacaoBem(val rotulo: String) {
    DISPONIVEL("Disponível"),
    ALUGADO("Alugado"),
    EM_DEVOLUCAO("Em devolução"),
    EM_MANUTENCAO("Em manutenção"),
    A_VENDA("À venda"),
    VENDIDO("Vendido"),
    EXTRAVIADO("Extraviado"),
    BAIXADO("Baixado");

    companion object {
        /** As que a edição da etiqueta (e a importação) pode escolher. */
        val EDITAVEIS = listOf(DISPONIVEL, A_VENDA, EM_MANUTENCAO, EXTRAVIADO)

        /** Fora do acervo: não aparecem nas listas por padrão nem no esperado dos inventários. */
        val FORA_DO_ACERVO = setOf(BAIXADO, VENDIDO)

        /** Fora do esperado do inventário: além das de fora do acervo, o que está com o cliente. */
        val FORA_DO_ESPERADO = FORA_DO_ACERVO + ALUGADO

        /** O que a lista de etiquetas mostra sem filtro. */
        val NO_ACERVO = entries.filter { it !in FORA_DO_ACERVO }

        /** Nomes de antes da 1.12.0 (planilhas antigas, app antigo): viram os de agora. */
        private val ANTIGOS = mapOf("EM_USO" to DISPONIVEL, "EM_ESTOQUE" to DISPONIVEL, "EMPRESTADO" to ALUGADO)

        /** Pelo código ou pelo nome ("Em manutenção"), sem diferenciar maiúsculas nem acento (importação de planilha). */
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

/** Pessoa responsável por bens (tabela responsaveis, V23). Não precisa ter login no sistema. */
@Entity
@Table(name = "responsaveis")
class ResponsavelEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 160)
    var nome: String,

    @Column(length = 120)
    var setor: String? = null,

    @Column(length = 60)
    var matricula: String? = null,

    @Column(length = 160)
    var email: String? = null,

    @Column(nullable = false)
    var ativo: Boolean = true,

    @Column(nullable = false)
    val criadoEm: LocalDateTime = LocalDateTime.now()
) {
    /** "Maria Souza (TI, mat. 123)". */
    val descricao: String
        get() = nome + listOfNotNull(setor?.takeIf { it.isNotBlank() }, matricula?.takeIf { it.isNotBlank() }?.let { "mat. $it" })
            .takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")").orEmpty()
}
