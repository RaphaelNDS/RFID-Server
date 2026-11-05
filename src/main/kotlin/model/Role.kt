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
    /**
     * Sem efeito: a gestão de usuários é só do ADMIN (SecurityConfig, rotas /admin/usuarios), porque quem cria
     * usuários poderia se dar qualquer permissão. Não aparece mais nas telas nem vai para GESTOR/USER; fica no
     * enum porque o banco pode ter permissões antigas gravadas com ele.
     */
    USUARIO,
    DASHBOARD
}