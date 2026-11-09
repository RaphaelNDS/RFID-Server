package org.example.config

import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * "Open in view" só onde precisa (1.5.2). Ligado (o padrão do Spring), ele abre a sessão do JPA no começo de cada
 * requisição e só fecha no fim, e a conexão do banco usada nela fica presa até lá. Nas telas, que leem coleções
 * lazy ao montar o HTML (ex.: as permissões na tela de usuários), ele continua. Ficam sem:
 *  - a API dos coletores (/api/android/): um lote de 500 leituras segurava uma conexão do começo ao fim, e com 20
 *    ou 30 coletores sincronizando juntos o pool esgotava (as requisições caíam com erro depois de 5 s e os coletores
 *    reenviavam). Lá tudo é feito nos serviços, com transação própria, e as respostas são DTOs;
 *  - o fluxo ao vivo do painel (/admin/sse/): a conexão ficaria presa enquanto a aba estivesse aberta.
 * spring.jpa.open-in-view fica false no application.yml (senão o Spring registraria o dele para tudo).
 */
@Configuration
class SessaoJpaTelasConfig(private val emf: EntityManagerFactory) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        val sessaoNasTelas = OpenEntityManagerInViewInterceptor().apply { entityManagerFactory = emf }
        registry.addWebRequestInterceptor(sessaoNasTelas)
            .addPathPatterns("/**")
            .excludePathPatterns(*SEM_SESSAO_NA_REQUISICAO)
    }

    companion object {
        val SEM_SESSAO_NA_REQUISICAO = arrayOf("/api/android/**", "/admin/sse/**", "/admin/sse")
    }
}
