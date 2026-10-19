package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.catalina.connector.Connector
import org.example.service.CertificadoHttps
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.Ssl
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter

/**
 * HTTPS do servidor (rfid.https.*):
 *  - HTTPS na [portaHttps] (padrão 8443) com o certificado de [CertificadoHttps];
 *  - a porta HTTP antiga (server.port, padrão 8082) continua aberta só para avisar quem chegar por ela:
 *    telas são REDIRECIONADAS para o HTTPS; a API responde erro 426 em JSON (um POST redirecionado viraria
 *    GET e perderia o corpo, uma falha difícil de entender no app).
 *
 * Os testes (MockMvc) não sobem o Tomcat, e o perfil de teste desliga o HTTPS.
 */
@Configuration
class HttpsConfig(
    @Value("\${rfid.https.habilitado:true}") private val habilitado: Boolean,
    @Value("\${rfid.https.porta:8443}") private val portaHttps: Int,
    @Value("\${server.port:8082}") private val portaHttp: Int,
    private val certificado: CertificadoHttps
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Roda depois do customizador do Spring Boot (que aplica server.port), por ter a menor precedência
    @Bean
    fun httpsNoTomcat(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory -> configurar(factory) }

    internal fun configurar(factory: TomcatServletWebServerFactory) {
        if (!habilitado) {
            log.warn("HTTPS desligado (rfid.https.habilitado=false): senhas, chave dos coletores e tokens trafegam SEM criptografia na porta {}", portaHttp)
            return
        }

        certificado.garantir()

        factory.port = portaHttps
        factory.ssl = Ssl().apply {
            isEnabled = true
            keyStore = "file:" + certificado.arquivo.absolutePath.replace('\\', '/')
            keyStorePassword = certificado.senha
            keyStoreType = "PKCS12"
            keyAlias = CertificadoHttps.ALIAS
        }
        // Cookie de sessão das telas só vai por HTTPS
        factory.session.cookie.secure = true

        factory.addAdditionalTomcatConnectors(Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL).apply {
            scheme = "http"
            port = portaHttp
            secure = false
            redirectPort = portaHttps
        })

        log.info("HTTPS ligado na porta {}; a porta {} (HTTP) só redireciona as telas e recusa a API", portaHttps, portaHttp)
    }

    /** Quem chega pela porta HTTP: tela vai para o HTTPS; API recebe 426 explicando o endereço certo. */
    @Bean
    @ConditionalOnProperty(name = ["rfid.https.habilitado"], havingValue = "true", matchIfMissing = true)
    fun redirecionamentoHttps(): FilterRegistrationBean<OncePerRequestFilter> {
        val filtro: OncePerRequestFilter = object : OncePerRequestFilter() {
            override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
                if (request.isSecure) {
                    chain.doFilter(request, response)
                    return
                }

                // Nome do servidor só se estiver no certificado (o header Host vem do cliente e pode ser forjado)
                val host = certificado.nomeConfiavel(request.serverName)
                val destino = "https://$host:$portaHttps${request.requestURI}" +
                    (request.queryString?.let { "?$it" } ?: "")

                if (request.requestURI.removePrefix(request.contextPath).startsWith("/api/")) {
                    log.warn("Chamada à API por HTTP recusada: {} {} (IP {}). O app precisa usar {}",
                        request.method, request.requestURI, request.remoteAddr, "https://$host:$portaHttps")
                    response.status = 426 // Upgrade Required
                    response.contentType = "application/json"
                    response.characterEncoding = "UTF-8"
                    response.writer.write(
                        """{"status":426,"mensagem":"Use HTTPS: https://$host:$portaHttps (o HTTP foi desligado por segurança)"}"""
                    )
                    return
                }

                log.debug("Tela pedida por HTTP redirecionada para o HTTPS: {}", request.requestURI)
                response.sendRedirect(destino)
            }
        }

        // Antes de tudo, inclusive do Spring Security (senão o HTTP iria para a tela de login antes do redirect)
        return FilterRegistrationBean<OncePerRequestFilter>(filtro).apply {
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/*")
            setName("redirecionamentoHttps")
        }
    }
}
