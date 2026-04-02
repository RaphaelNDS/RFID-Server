package org.example.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig {

    @Bean
    fun passwordEncoder() = BCryptPasswordEncoder()

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {

        http
            .csrf { it.disable() }

            .authorizeHttpRequests {

                it.requestMatchers(
                    "/login",
                    "/setup",
                    "/setup/**",
                    "/h2-console/**",
                    "/css/**",
                    "/js/**",
                    "/images/**",
                    "/webjars/**"
                ).permitAll()

                it.requestMatchers("/admin/**").authenticated()
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
                it.logoutSuccessUrl("/login")
                it.invalidateHttpSession(true)
                it.deleteCookies("JSESSIONID")
            }

        http.headers { headers ->
            headers.frameOptions { it.disable() }
        }

        return http.build()
    }
}