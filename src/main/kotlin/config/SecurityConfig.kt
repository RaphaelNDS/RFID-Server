package org.example.config

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authorization.AuthorizationEventPublisher
import org.springframework.security.authorization.SpringAuthorizationEventPublisher
import org.example.service.PoliticaSenha
import org.example.service.SessaoOperadorService
import org.example.service.TentativasLoginService
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.security.web.csrf.CsrfException
import java.time.Clock
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig {

    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    private val h2Console = AntPathRequestMatcher("/h2-console/**")

    // Endpoints JSON antigos (sem formulário HTML), que exigem login de usuário.
    // Ficam fora do CSRF porque não são telas do navegador. O app Android usa /api/android/**,
    // que tem cadeia própria (dispositivosFilterChain).
    private val apisDispositivos = arrayOf(
        AntPathRequestMatcher("/api/rfid/**"),
        AntPathRequestMatcher("/api/tags/**")
    )

    @Bean
    fun passwordEncoder() = BCryptPasswordEncoder()

    // Faz o Spring Security publicar eventos de acesso negado (logados em SecurityEventLogger)
    @Bean
    fun authorizationEventPublisher(publisher: ApplicationEventPublisher): AuthorizationEventPublisher =
        SpringAuthorizationEventPublisher(publisher)

    /**
     * Cadeia exclusiva do app Android (tudo em /api/android): sem sessão, sem tela de login e sem CSRF.
     * O coletor se identifica pela chave no header X-Chave-Dispositivo (ver ChaveDispositivoFilter).
     * Sem chave válida a resposta é 401 em JSON, nunca um redirecionamento para /login.
     */
    @Bean
    @Order(1)
    fun dispositivosFilterChain(
        http: HttpSecurity,
        @Value("\${rfid.dispositivos.chaves:}") chaves: String,
        sessoesOperador: SessaoOperadorService,
        corteLoginOperador: CorteLoginOperador,
        relogio: Clock
    ): SecurityFilterChain {

        val filtroChave = ChaveDispositivoFilter(chaves)

        http
            .securityMatcher("/api/android/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(filtroChave, BasicAuthenticationFilter::class.java)
            // Depois do coletor, o operador logado nele (X-Sessao-Operador); login e ping não exigem
            .addFilterAfter(OperadorFilter(sessoesOperador, corteLoginOperador, relogio), ChaveDispositivoFilter::class.java)
            .authorizeHttpRequests { it.anyRequest().hasRole(ChaveDispositivoFilter.ROLE) }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    log.warn("Acesso não autenticado à API de dispositivos: {} {} (IP: {})", request.method, request.requestURI, request.remoteAddr)
                    response.status = HttpStatus.UNAUTHORIZED.value()
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.characterEncoding = "UTF-8"
                    response.writer.write("""{"status":401,"mensagem":"Dispositivo não autenticado"}""")
                }
                // Operador sem a permissão da ação (@PreAuthorize): 403 em JSON, como o resto da API do coletor
                it.accessDeniedHandler { request, response, _ ->
                    log.warn("Operador sem permissão no coletor {}: {} {}",
                        SecurityContextHolder.getContext().authentication?.principal, request.method, request.requestURI)
                    response.status = HttpStatus.FORBIDDEN.value()
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    response.characterEncoding = "UTF-8"
                    response.writer.write("""{"status":403,"mensagem":"Seu usuário não tem permissão para esta ação"}""")
                }
            }

        return http.build()
    }

    @Bean
    fun filterChain(http: HttpSecurity, tentativasLogin: TentativasLoginService, politicaSenha: PoliticaSenha): SecurityFilterChain {

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

            // Barra email/IP bloqueado por excesso de senhas erradas antes de conferir a senha
            .addFilterBefore(BloqueioLoginFilter(tentativasLogin), UsernamePasswordAuthenticationFilter::class.java)

            .formLogin {
                it.loginPage("/login")
                // Vai para /redirect; quem entrar com senha fora das regras é levado à troca de senha
                it.successHandler(SucessoLoginHandler(politicaSenha))
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
