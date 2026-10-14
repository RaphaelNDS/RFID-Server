package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "usuarios")
class UsuarioEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    var nome: String,

    @Column(unique = true)
    var email: String,

    var senha: String,

    @Enumerated(EnumType.STRING)
    var role: Role,

    @OneToMany(mappedBy = "usuario", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var permissoes: MutableList<PermissaoEntity> = mutableListOf(),

    /** Quando pediu uma senha nova pelo "Esqueceu a senha?" (nulo = nenhum pedido em aberto). */
    @Column(name = "senha_pedida_em")
    var senhaPedidaEm: LocalDateTime? = null,

    /** Entrou com senha temporária gerada pelo ADMIN: precisa trocá-la antes de usar o sistema. */
    @Column(name = "trocar_senha")
    var trocarSenha: Boolean = false
)
