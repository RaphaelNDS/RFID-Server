package org.example.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate

@Entity
@Table(name = "tags")
class TagEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    var codigoInterno: String = "",

    @Column(nullable = false, unique = true)
    val tagHash: String,

    @Column(nullable = false, unique = true)
    val tagReal: String,

    @Column(name = "tipo_equipamento_id")
    var tipoEquipamentoId: Long? = null,

    @Column(name = "marca_id")
    var marcaId: Long? = null,

    @Column(name = "modelo_id")
    var modeloId: Long? = null,

    var patrimonio: String = "",
    var numeroSerie: String = "",

    /** Muda a cada gravação (V17): a tela de edição recusa salvar por cima da mudança de outra pessoa. */
    @Version
    var versao: Long = 0,

    // Gestão de patrimônio (1.6.0, V23)

    /** Onde o bem está: escolhido no cadastro ou atualizado quando um inventário finalizado o encontra. */
    @Column(name = "local_id")
    var localId: Long? = null,

    @Column(name = "responsavel_id")
    var responsavelId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var situacao: SituacaoBem = SituacaoBem.DISPONIVEL,

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var baixaMotivo: MotivoBaixa? = null,

    var baixaEm: LocalDate? = null,

    @Column(length = 500)
    var baixaObservacao: String? = null,

    var baixaPor: String? = null,

    // Valor de aquisição, data e nota fiscal (1.8.0, V25): fora da tela desde a 1.12.0, continuam no banco

    @Column(name = "valor_aquisicao", precision = 14, scale = 2)
    var valorAquisicao: BigDecimal? = null,

    @Column(name = "data_aquisicao")
    var dataAquisicao: LocalDate? = null,

    @Column(name = "nota_fiscal", length = 60)
    var notaFiscal: String? = null,

    /** 1.26.0: garantia do fabricante (V39): até quando e com quem. */
    @Column(name = "garantia_ate")
    var garantiaAte: LocalDate? = null,

    @Column(name = "garantia_fornecedor", length = 160)
    var garantiaFornecedor: String? = null
) {
    val baixado: Boolean get() = situacao == SituacaoBem.BAIXADO

    /** 1.26.0: na garantia no dia [dia] (inclusive o último dia). */
    fun naGarantia(dia: LocalDate): Boolean = garantiaAte?.let { !dia.isAfter(it) } == true

    /** Baixado ou vendido (1.12.0): não está mais no acervo da locadora. */
    val foraDoAcervo: Boolean get() = situacao in SituacaoBem.FORA_DO_ACERVO
}

