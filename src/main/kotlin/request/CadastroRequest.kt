package org.example.request

data class CadastroRequest(
    var tag: String = "",
    var codigoInterno: String = "",
    var patrimonio: String = "",
    var numeroSerie: String = "",

    var tipoEquipamentoId : Long? = null,
    var marcaId: Long? = null,
    var modeloId: Long? = null,

    /** Edição: a versão da etiqueta quando a tela abriu (null no cadastro e no coletor: não confere). */
    var versao: Long? = null,

    // Opcionais
    var localId: Long? = null,
    /** Código da situação (DISPONIVEL, EXTRAVIADO); vazio = No estoque no cadastro e a situação atual na edição. */
    var situacao: String? = null
)
