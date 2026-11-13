package org.example.model

import jakarta.persistence.*
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var situacao: SituacaoBem = SituacaoBem.DISPONIVEL,

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var baixaMotivo: MotivoBaixa? = null,

    var baixaEm: LocalDate? = null,

    @Column(length = 500)
    var baixaObservacao: String? = null,

    var baixaPor: String? = null

    // 2.0.0: as colunas responsavel_id (V23), valor_aquisicao, data_aquisicao e nota_fiscal (V25) continuam no
    // banco, sem uso (a versão completa, branch locadora, ainda as tem)
) {
    val baixado: Boolean get() = situacao == SituacaoBem.BAIXADO

    /** Baixado: não está mais no acervo. */
    val foraDoAcervo: Boolean get() = situacao in SituacaoBem.FORA_DO_ACERVO
}

