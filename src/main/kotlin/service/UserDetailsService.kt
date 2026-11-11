package org.example.service

import org.example.repository.UserRepository
import org.example.security.UserPrincipal
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

@Service
class CustomUserDetailsService(
    private val usuarioRepository: UserRepository,
    private val relogio: Clock,
    @org.springframework.beans.factory.annotation.Value("\${rfid.senha.prazo-temporaria:48h}") private val prazoTemporaria: Duration
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {

        // Login sem diferenciar maiúsculas nem espaços nas pontas (UserService.normalizarEmail)
        val user = usuarioRepository.findByEmailFetchPermissoes(username.trim())
            ?: throw UsernameNotFoundException("Usuário não encontrado")

        val authorities = mutableListOf<GrantedAuthority>()

        authorities.add(SimpleGrantedAuthority("ROLE_${user.role.name}"))

        user.permissoes.forEach {
            authorities.add(
                SimpleGrantedAuthority("${it.modulo}_${it.acao}")
            )
        }

        return UserPrincipal(
            id = user.id!!,
            nome = user.nome,
            email = user.email,
            senha = user.senha,
            authoritiesList = authorities,
            trocarSenha = user.trocarSenha,
            ativo = user.ativo,
            // Senha temporária vale por [prazoTemporaria]: depois disso, só pedindo outra ao administrador
            senhaTemporariaVencida = user.trocarSenha && user.senhaTemporariaEm?.plus(prazoTemporaria)
                ?.isBefore(LocalDateTime.now(relogio)) == true
        )
    }
}
