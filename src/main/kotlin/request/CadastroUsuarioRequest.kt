package org.example.request

import org.example.model.Role

data class CadastroUsuarioRequest(
val nome: String,
val email: String,
val senha: String,
val role: Role,
// Anuláveis de propósito: sem nenhuma caixa marcada, o formulário não envia o campo e o Spring entrega null
val telas: List<String>? = null,
val permissoes: List<String>? = null
)