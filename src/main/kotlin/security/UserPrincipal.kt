package org.example.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails


class UserPrincipal(
    val id: Long,
    val nome: String,
    val email: String,
    private val senha: String,
    private val authoritiesList: Collection<GrantedAuthority>,
    /** Entrou com senha temporária: precisa criar uma senha nova antes de usar o sistema. */
    val trocarSenha: Boolean = false
) : UserDetails {

    override fun getAuthorities(): Collection<GrantedAuthority> = authoritiesList

    override fun getPassword(): String = senha

    override fun getUsername(): String = email

    override fun isAccountNonExpired() = true
    override fun isAccountNonLocked() = true
    override fun isCredentialsNonExpired() = true
    override fun isEnabled() = true
}
