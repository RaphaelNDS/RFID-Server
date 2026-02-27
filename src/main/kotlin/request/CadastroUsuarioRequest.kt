package org.example.request

import org.example.model.Acao
import org.example.model.Modulo
import org.example.model.Role

data class CadastroUsuarioRequest(
val nome: String,
val email: String,
val senha: String,
val role: Role,
val telas: List<String> = emptyList(),
val permissoes: List<String> = emptyList()
)

data class PermissaoRequest(
    val modulo: Modulo,
    val acao: Acao
)