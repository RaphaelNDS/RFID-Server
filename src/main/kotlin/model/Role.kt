package org.example.model

enum class Role {
    ADMIN,
    GESTOR,
    USER,
    CUSTOM
}

enum class Acao {
    CREATE, READ, UPDATE, DELETE
}

enum class Modulo {
    CATALOGO,
    LEITURA,
    RFID,
    USUARIO,
    DASHBOARD
}