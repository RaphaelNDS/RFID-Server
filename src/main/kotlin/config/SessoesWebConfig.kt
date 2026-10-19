package org.example.config

import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.core.session.SessionRegistryImpl
import org.springframework.security.web.session.HttpSessionEventPublisher

/**
 * Lista das sessões web abertas, para o servidor conseguir derrubar as de um usuário na hora (desativado pelo
 * ADMIN ou com a senha trocada). Sem isso a sessão continuaria valendo até expirar, mesmo com o usuário desativado.
 */
@Configuration
class SessoesWebConfig {

    @Bean
    fun sessionRegistry(): SessionRegistry = SessionRegistryImpl()

    // Avisa o registro quando a sessão termina (logout ou expiração), senão ele guardaria sessões mortas para sempre
    @Bean
    fun httpSessionEventPublisher() = ServletListenerRegistrationBean(HttpSessionEventPublisher())
}
