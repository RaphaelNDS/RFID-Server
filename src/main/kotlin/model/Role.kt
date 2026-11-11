package org.example.model

/**
 * Perfil do usuário. ADMIN, GESTOR e USER valem para todos os módulos; CUSTOM é o que o ADMIN marcar. Os perfis de
 * FUNÇÃO (1.15.0: EXPEDICAO, CONFERENTE, TECNICO) têm permissões prontas por módulo ([permissoesFixas]), pensados
 * para o uso interno da locadora.
 */
enum class Role(val rotulo: String, val descricao: String) {
    ADMIN("Administrador", "Acesso total, inclusive gestão de usuários."),
    GESTOR("Gestor", "Consulta, cadastra e edita em todos os módulos. Não exclui nem gerencia usuários."),
    USER("Usuário", "Só consulta: vê tudo, não altera nada."),
    EXPEDICAO("Expedição", "Pedidos de locação: cria, separa e registra a saída e a devolução. Vê etiquetas, leituras e catálogo."),
    CONFERENTE("Conferente", "Recebe e confere as devoluções, anexa fotos e manda para a manutenção. Vê etiquetas, leituras e catálogo."),
    TECNICO("Técnico", "Manutenções: abre, conclui e cancela; edita a etiqueta e anexa fotos. Vê leituras, catálogo e pedidos."),
    CUSTOM("Personalizado", "Escolha os módulos e o que o usuário pode fazer neles.");

    /** Perfil de função: as permissões são as de [permissoesFixas], não as caixas da tela. */
    val deFuncao: Boolean get() = this in FUNCOES

    /** As permissões dos perfis de função (vazio para os outros: ver UserService.permissoesDoPerfil). */
    fun permissoesFixas(): List<Pair<Modulo, Acao>> = when (this) {
        EXPEDICAO -> lista(Modulo.LOCACAO to listOf(Acao.READ, Acao.CREATE, Acao.UPDATE),
            Modulo.RFID to listOf(Acao.READ), Modulo.LEITURA to listOf(Acao.READ), Modulo.CATALOGO to listOf(Acao.READ))
        CONFERENTE -> lista(Modulo.LOCACAO to listOf(Acao.READ, Acao.UPDATE), Modulo.MANUTENCAO to listOf(Acao.READ, Acao.CREATE),
            Modulo.RFID to listOf(Acao.READ, Acao.UPDATE), Modulo.LEITURA to listOf(Acao.READ), Modulo.CATALOGO to listOf(Acao.READ))
        TECNICO -> lista(Modulo.MANUTENCAO to listOf(Acao.READ, Acao.CREATE, Acao.UPDATE), Modulo.RFID to listOf(Acao.READ, Acao.UPDATE),
            Modulo.LOCACAO to listOf(Acao.READ), Modulo.LEITURA to listOf(Acao.READ), Modulo.CATALOGO to listOf(Acao.READ))
        else -> emptyList()
    }

    companion object {
        val FUNCOES = setOf(EXPEDICAO, CONFERENTE, TECNICO)

        private fun lista(vararg pares: Pair<Modulo, List<Acao>>) = pares.flatMap { (m, acoes) -> acoes.map { m to it } }
    }
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
    /** 1.15.0: clientes, pedidos de locação, saída, devolução e conferência, disponibilidade e o painel do dia. */
    LOCACAO("Locação"),
    /** 1.15.0: ordens de manutenção. */
    MANUTENCAO("Manutenção")
}
