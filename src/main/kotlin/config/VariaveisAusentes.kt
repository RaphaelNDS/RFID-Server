package org.example.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Variáveis do Windows usadas nas pastas de backup do config (1.15.0). Em 11/11/2026 o servidor não subiu porque a
 * OneDriveCommercial não chegou ao processo ("Could not resolve placeholder 'OneDriveCommercial'"): o Spring resolve
 * ${...} antes de criar os serviços, e uma variável ausente derrubava a subida inteira.
 *
 * Agora, se uma destas variáveis falta, ela ganha um [MARCADOR] no lugar. Quem monta uma pasta com ela
 * ([pastaIndisponivel]) vê o marcador e desliga só aquele backup, com aviso no log, em vez de o servidor não subir.
 */
class VariaveisAusentes : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val ausentes = VARIAVEIS.filter { environment.getProperty(it).isNullOrBlank() }
        if (ausentes.isEmpty()) return
        environment.propertySources.addLast(MapPropertySource(FONTE, ausentes.associateWith { "$MARCADOR$it$MARCADOR" }))
    }

    companion object {
        /** As do OneDrive (empresa e pessoal): as que o config de produção usa nas pastas de backup. */
        val VARIAVEIS = listOf("OneDriveCommercial", "OneDrive", "OneDriveConsumer")

        const val MARCADOR = "#variavel-ausente#"
        private const val FONTE = "variaveisAusentes"

        /**
         * A pasta configurada usa uma variável que não existe neste processo: devolve o nome dela (para o log), ou
         * null se a pasta está completa.
         */
        fun pastaIndisponivel(pasta: String?): String? {
            val texto = pasta ?: return null
            val inicio = texto.indexOf(MARCADOR).takeIf { it >= 0 } ?: return null
            return texto.substring(inicio + MARCADOR.length).substringBefore(MARCADOR)
        }
    }
}
