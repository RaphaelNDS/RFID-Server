package org.example.model

import jakarta.persistence.*

@Entity
@Table(name = "permissoes")
data class PermissaoEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Enumerated(EnumType.STRING)
    val modulo: Modulo,

    @Enumerated(EnumType.STRING)
    val acao: Acao,

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    val usuario: UsuarioEntity
)