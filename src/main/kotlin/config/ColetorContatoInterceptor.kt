package org.example.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.security.OperadorNoColetor
import org.example.service.ColetorService
import org.example.service.DiagnosticoColetor
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Registra cada contato ACEITO de um coletor em /api/android (chave válida e, quando exigido, operador válido:
 * os filtros de segurança já rodaram) para a tela Coletores. A versão do app vem nos headers
 * X-Versao-App e X-Versao-Codigo, que são de fora: valor inválido é ignorado, nunca derruba a requisição.
 * O mesmo vale para o diagnóstico do app 1.7.x (fila, última sincronização, espaço livre, Android, modelo e relógio).
 */
class ColetorContatoInterceptor(private val coletores: ColetorService) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val auth = SecurityContextHolder.getContext().authentication ?: return true
        if (!auth.isAuthenticated) return true

        coletores.registrarContato(
            nome = auth.name,
            ip = request.remoteAddr,
            versaoApp = versaoApp(request.getHeader(HEADER_VERSAO_APP)),
            codigoVersao = codigoVersao(request.getHeader(HEADER_VERSAO_CODIGO)),
            operador = (auth.principal as? OperadorNoColetor)?.operador?.nome,
            diagnostico = diagnostico(request::getHeader)
        )
        return true
    }

    companion object {
        const val HEADER_VERSAO_APP = "X-Versao-App"
        const val HEADER_VERSAO_CODIGO = "X-Versao-Codigo"
        private const val TAMANHO_MAXIMO_VERSAO = 50

        // Diagnóstico do coletor (app 1.7.x): todos opcionais
        const val HEADER_FILA_PENDENTES = "X-Fila-Pendentes"
        const val HEADER_FILA_MAIS_ANTIGA = "X-Fila-Mais-Antiga"
        const val HEADER_ULTIMA_SINC_OK = "X-Ultima-Sinc-Ok"
        /** MB livres na pasta do app. */
        const val HEADER_ESPACO_LIVRE = "X-Espaco-Livre"
        const val HEADER_ANDROID = "X-Android"
        const val HEADER_MODELO = "X-Modelo"
        /** Hora do aparelho (ISO-8601 com fuso) no momento da requisição. */
        const val HEADER_RELOGIO_COLETOR = "X-Relogio-Coletor"

        /**
         * Diagnóstico pelos cabeçalhos. Cada valor é conferido sozinho: um inválido vira null e não derruba os
         * outros nem a requisição. A fila só conta como informada com um número válido.
         */
        fun diagnostico(header: (String) -> String?): DiagnosticoColetor {
            val fila = header(HEADER_FILA_PENDENTES)?.trim()?.toIntOrNull()?.takeIf { it in 0..MAXIMO_FILA }
            return DiagnosticoColetor(
                filaInformada = fila != null,
                filaPendentes = fila,
                filaMaisAntiga = if (fila != null && fila > 0) horaLocal(header(HEADER_FILA_MAIS_ANTIGA)) else null,
                ultimaSincOk = horaLocal(header(HEADER_ULTIMA_SINC_OK)),
                espacoLivreMb = header(HEADER_ESPACO_LIVRE)?.trim()?.toLongOrNull()?.takeIf { it in 0..MAXIMO_ESPACO_MB },
                versaoAndroid = textoSimples(header(HEADER_ANDROID), 20),
                modelo = textoSimples(header(HEADER_MODELO), 100),
                relogioColetor = hora(header(HEADER_RELOGIO_COLETOR))
            )
        }

        private const val MAXIMO_FILA = 10_000_000
        private const val MAXIMO_ESPACO_MB = 100_000_000L   // 100 TB: acima disso o número não é de verdade

        /** ISO-8601 com fuso, entre 2020 e 2100 (fora disso o relógio do aparelho não está configurado). */
        fun hora(texto: String?): OffsetDateTime? =
            texto?.trim()?.takeIf { it.length <= 40 }
                ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                ?.takeIf { it.year in 2020..2100 }

        private fun horaLocal(texto: String?): LocalDateTime? =
            hora(texto)?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime()

        /** Versão do Android e modelo: letras, números, espaço e . - _ ( ) / + ? (o app troca acento por ?). */
        fun textoSimples(texto: String?, maximo: Int): String? =
            texto?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= maximo && it.all { c -> c in ' '..'~' && (c.isLetterOrDigit() || c in " .-_()/+?") } }

        /** "1.4.0": só letras, números, ponto, hífen, + e espaço; no máximo 50 caracteres. Fora disso, ignora. */
        fun versaoApp(texto: String?): String? =
            texto?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= TAMANHO_MAXIMO_VERSAO && it.all { c -> c.isLetterOrDigit() || c in ".-+ _" } }

        fun codigoVersao(texto: String?): Int? = texto?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }
}

@Configuration
class ColetoresWebConfig(private val coletores: ColetorService) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(ColetorContatoInterceptor(coletores)).addPathPatterns("/api/android/**")
    }
}
