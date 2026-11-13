package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/** Formato de um campo personalizado (1.22.0). */
enum class FormatoCampo(val rotulo: String) {
    TEXTO("Texto"),
    NUMERO("Número"),
    DATA("Data"),
    LISTA("Lista de opções"),
    SIM_NAO("Sim ou não")
}

/** Campo personalizado de um tipo de equipamento (tabela campos_tipo, V36). */
@Entity
@Table(name = "campos_tipo")
class CampoTipoEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val tipoEquipamentoId: Long,

    @Column(nullable = false, length = 80)
    var nome: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var formato: FormatoCampo,

    /** Só na LISTA: as opções separadas por ";". */
    @Column(length = 1000)
    var opcoes: String? = null,

    @Column(nullable = false)
    var obrigatorio: Boolean = false,

    @Column(nullable = false)
    var ordem: Int = 0,

    @Column(nullable = false)
    var ativo: Boolean = true,

    @Column(nullable = false)
    val criadoEm: LocalDateTime = LocalDateTime.now()
) {
    val listaOpcoes: List<String> get() = opcoes?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

/** Valor de um campo personalizado num equipamento (tabela valores_campo, V36). */
@Entity
@Table(name = "valores_campo")
class ValorCampoEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val tagId: Long,

    @Column(nullable = false)
    val campoId: Long,

    @Column(nullable = false, length = 500)
    var valor: String
)
