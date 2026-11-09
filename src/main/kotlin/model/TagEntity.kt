package org.example.model

import jakarta.persistence.*

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
    var situacao: SituacaoBem = SituacaoBem.EM_USO,

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var baixaMotivo: MotivoBaixa? = null,

    var baixaEm: java.time.LocalDate? = null,

    @Column(length = 500)
    var baixaObservacao: String? = null,

    var baixaPor: String? = null
) {
    val baixado: Boolean get() = situacao == SituacaoBem.BAIXADO
}

