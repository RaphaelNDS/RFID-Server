package org.example.model

import jakarta.persistence.*
import org.springframework.context.support.BeanDefinitionDsl

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
    var permissoes: MutableList<PermissaoEntity> = mutableListOf()
)