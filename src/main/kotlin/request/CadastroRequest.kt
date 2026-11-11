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

    // Gestão de patrimônio (1.6.0): opcionais
    var localId: Long? = null,
    var responsavelId: Long? = null,
    /** Código da situação (DISPONIVEL...); vazio = Disponível no cadastro e a situação atual na edição. BAIXADO só pela baixa. */
    var situacao: String? = null,

    // Valor (1.8.0): texto como veio do formulário ou da planilha ("1.234,56", "2026-01-15" ou "15/01/2026"); vazio = sem valor
    var valorAquisicao: String? = null,
    var dataAquisicao: String? = null,
    var notaFiscal: String? = null
)



