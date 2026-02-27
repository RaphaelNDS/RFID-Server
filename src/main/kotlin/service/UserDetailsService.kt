package org.example.service

import org.example.repository.UserRepository
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class CustomUserDetailsService(
    private val usuarioRepository: UserRepository
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {

        val user = usuarioRepository.findByEmailFetchPermissoes(username)
            ?: throw UsernameNotFoundException("Usuário não encontrado")

        val authorities = mutableListOf<GrantedAuthority>()

        authorities.add(SimpleGrantedAuthority("ROLE_${user.role.name}"))

        user.permissoes.forEach {
            authorities.add(
                SimpleGrantedAuthority("${it.modulo}_${it.acao}")
            )
        }

        return User(
            user.email,
            user.senha,
            authorities
        )
    }
}