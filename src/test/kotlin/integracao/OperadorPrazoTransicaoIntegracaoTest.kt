package org.example.integracao

import org.example.suporte.IntegracaoBase
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Período de transição do login de operador já encerrado: com prazo de sincronização zero, o servidor
 * está "depois do prazo" assim que sobe. A fila antiga (sem operador) não pode mais entrar, nem com
 * um X-Momento anterior à instalação da V4 — o momento vem do coletor e não pode, sozinho, abrir a porta.
 */
@TestPropertySource(properties = ["rfid.operador.janela-sincronizacao=0s"])
class OperadorPrazoTransicaoIntegracaoTest : IntegracaoBase() {

    @Test
    fun `depois do prazo de transicao a leitura sem operador e recusada mesmo com X-Momento antigo`() {
        val muitoAntes = LocalDateTime.now().minusDays(30).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

        mvc.perform(
            post("/api/android/naocadastrada").header("X-Chave-Dispositivo", CHAVE_COLETOR).header("X-Momento", muitoAntes)
                .contentType(MediaType.APPLICATION_JSON).content("""{"tag":"E-ANTIGA"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.codigo").value("SESSAO_OPERADOR"))
    }
}
