package org.example.dto

import org.example.model.Role

class UsuarioDTO {
    var nome: String? = null
    var email: String? = null
    var senha: String? = null
    var role: Role? = null
    var telas: List<String>? = null
    var acoes: List<String>? = null
}