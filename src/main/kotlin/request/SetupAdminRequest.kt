package org.example.request

data class SetupAdminRequest(
    val nome: String,
    val email: String,
    val senha: String
)