package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/** Situação do bem (tags.situacao, V23). BAIXADO só pela baixa (RfidService.darBaixa), nunca pela edição. */
enum class SituacaoBem(val rotulo: String) {
    EM_USO("Em uso"),
    EM_ESTOQUE("Em estoque"),
    EM_MANUTENCAO("Em manutenção"),
    EMPRESTADO("Emprestado"),
    EXTRAVIADO("Extraviado"),
    BAIXADO("Baixado");

    companion object {
        /** As que a edição da etiqueta pode escolher (a baixa tem tela própria). */
        val EDITAVEIS = entries.filter { it != BAIXADO }

        /** Pelo código ou pelo nome ("Em manutenção"), sem diferenciar maiúsculas nem acento (importação de planilha). */
        fun de(texto: String?): SituacaoBem? = porCodigoOuNome(texto, entries) { it.rotulo }
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

/** Código (EM_USO) ou nome ("Em uso"), sem diferenciar maiúsculas, acento, espaço ou "/". */
internal fun <E : Enum<E>> porCodigoOuNome(texto: String?, valores: List<E>, rotulo: (E) -> String): E? {
    fun limpar(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
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
