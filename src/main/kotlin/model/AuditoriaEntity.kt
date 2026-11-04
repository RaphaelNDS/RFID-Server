package org.example.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/** O que a auditoria registra (filtro da tela). */
enum class AreaAuditoria(val rotulo: String) {
    USUARIOS("Usuários"),
    SENHAS("Senhas e acesso"),
    CATALOGO("Catálogo"),
    APP("App dos coletores"),
    EXPORTACAO("Exportações"),
    BACKUP("Backup")
}

/** Uma ação de usuário ou de configuração (tabela auditoria, V13). Somente leitura depois de gravada. */
@Entity
@Table(name = "auditoria")
class AuditoriaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val momento: LocalDateTime,

    /** Email de quem fez na web, nome do operador no coletor, ou "sistema". */
    @Column(nullable = false)
    val usuario: String,

    /** "Web 10.0.0.5", "Coletor coletor-01" ou "Sistema". */
    @Column(nullable = false)
    val origem: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val area: AreaAuditoria,

    @Column(nullable = false, length = 80)
    val acao: String,

    /** Sobre quem ou o quê (email do usuário, nome do item do catálogo, versão do app...). */
    val alvo: String? = null,

    @Column(length = 1000)
    val detalhe: String? = null
)
