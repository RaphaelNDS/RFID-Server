package org.example.model

/**
 * Perfil do usuário. ADMIN, GESTOR e USER valem para todos os módulos; CUSTOM é o que o ADMIN marcar.
 *
 * 2.0.0: os perfis de função da versão de locação (EXPEDICAO, CONFERENTE, TECNICO) saíram; a V34 passou quem os tinha
 * para CUSTOM, com as mesmas permissões (as de locação e manutenção viraram as de Movimentações).
 */
enum class Role(val rotulo: String, val descricao: String) {
    ADMIN("Administrador", "Acesso total, inclusive gestão de usuários."),
    GESTOR("Gestor", "Consulta, cadastra e edita em todos os módulos. Não exclui nem gerencia usuários."),
    USER("Usuário", "Só consulta: vê tudo, não altera nada."),
    CUSTOM("Personalizado", "Escolha os módulos e o que o usuário pode fazer neles.");
}

enum class Acao {
    CREATE, READ, UPDATE, DELETE
}

enum class Modulo(val rotulo: String) {
    CATALOGO("Catálogo"),
    LEITURA("Leituras"),
    RFID("Etiquetas (RFID)"),
    /**
     * Sem efeito: a gestão de usuários é só do ADMIN (SecurityConfig, rotas /admin/usuarios), porque quem cria
     * usuários poderia se dar qualquer permissão. Não aparece mais nas telas nem vai para GESTOR/USER; fica no
     * enum porque o banco pode ter permissões antigas gravadas com ele.
     */
    USUARIO("Usuários"),
    DASHBOARD("Relatórios"),
    /** 2.0.0: saída e entrada de equipamentos (Ver: a lista e o comprovante; Criar: registrar saída e entrada). */
    MOVIMENTO("Movimentações")
}
