package org.example.config

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authorization.AuthorizationEventPublisher
import org.springframework.security.authorization.SpringAuthorizationEventPublisher
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig {

    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    private val h2Console = AntPathRequestMatcher("/h2-console/**")

    // Endpoints chamados pelos leitores/app Android (JSON, sem formulário HTML).
    // Ficam fora do CSRF porque não são telas do navegador; continuam exigindo login.
    private val apisDispositivos = arrayOf(
        AntPathRequestMatcher("/api/android/**"),
        AntPathRequestMatcher("/api/rfid/**"),
        AntPathRequestMatcher("/api/tags/**")
    )

    @Bean
    fun passwordEncoder() = BCryptPasswordEncoder()

    // Faz o Spring Security publicar eventos de acesso negado (logados em SecurityEventLogger)
    @Bean
    fun authorizationEventPublisher(publisher: ApplicationEventPublisher): AuthorizationEventPublisher =
        SpringAuthorizationEventPublisher(publisher)

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {

        http
            .csrf {
                it.ignoringRequestMatchers(*apisDispositivos)
                // O console do H2 não envia token CSRF nos formulários dele
                it.ignoringRequestMatchers(h2Console)
            }

            .authorizeHttpRequests {

                it.requestMatchers(
                    "/login",
                    "/error",
                    "/setup",
                    "/setup/**",
                    "/css/**",
                    "/js/**",
                    "/images/**",
                    "/img/**",
                    "/webjars/**"
                ).permitAll()

                it.requestMatchers(h2Console).hasRole("ADMIN")
                // Gestão de usuários fica só com o ADMIN: quem cria usuários poderia se dar qualquer permissão
                it.requestMatchers("/admin/usuarios/**").hasRole("ADMIN")
                // O resto de /admin é liberado por permissão (MODULO_ACAO) via @PreAuthorize em cada controller.
                // Todo controller novo em /admin PRECISA ter @PreAuthorize, senão fica aberto a qualquer usuário logado.
                it.requestMatchers("/admin/**").authenticated()
                it.requestMatchers("/gestor/**").hasRole("GESTOR")
                it.requestMatchers("/user/**").hasRole("USER")
                it.requestMatchers("/dashboard").authenticated()
                it.requestMatchers("/perfil/**").authenticated()

                it.anyRequest().authenticated()
            }

            .formLogin {
                it.loginPage("/login")
                it.defaultSuccessUrl("/redirect", true)
                it.permitAll()
            }

            .logout {
                it.logoutUrl("/logout")
                it.logoutSuccessUrl("/login?logout")
                it.invalidateHttpSession(true)
                it.deleteCookies("JSESSIONID")
            }

            .exceptionHandling {
                it.accessDeniedHandler(accessDeniedHandlerComLog())
            }

        // sameOrigin em vez de disable: o console do H2 usa frames, mas outros sites não podem embutir o sistema
        http.headers { headers ->
            headers.frameOptions { it.sameOrigin() }
        }

        return http.build()
    }

    private fun accessDeniedHandlerComLog(): AccessDeniedHandler {
        val padrao = AccessDeniedHandlerImpl()

        return AccessDeniedHandler { request, response, ex ->
            if (ex is CsrfException) {
                log.warn(
                    "Requisição bloqueada por token CSRF ausente ou inválido: {} {} (IP: {}, usuário: {})",
                    request.method, request.requestURI, request.remoteAddr,
                    SecurityContextHolder.getContext().authentication?.name ?: "anônimo"
                )
            }
            padrao.handle(request, response, ex)
        }
    }
}
