package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.Role
import org.example.repository.SessaoOperadorRepository
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** "Esqueci o PIN" das Configurações do coletor: só um ADMIN libera, e sem mexer na sessão do operador. */
class LiberarPinIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var sessoes: SessaoOperadorRepository

    // O bloqueio de login é em memória e compartilhado entre testes: cada teste usa email e IP próprios
    private val ip = "10.77.${(1..250).random()}.${(1..250).random()}"

    private fun liberar(email: String, senha: String = OperadorTeste.SENHA, chave: String = CHAVE_COLETOR) =
        mvc.perform(post("/api/android/pin/liberar").header(OperadorTeste.HEADER_CHAVE, chave)
            .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","senha":"$senha"}""")
            .with { it.remoteAddr = ip; it })

    @Test
    fun `admin libera o PIN sem abrir sessao e sem derrubar o operador do coletor`() {
        operadores.criarUsuario(email = "pin-operador@x.com")
        operadores.criarUsuario(email = "pin-admin@x.com", role = Role.ADMIN)
        operadores.entrar(mvc, "pin-operador@x.com")
        val abertasAntes = sessoes.findAll().count { it.encerradaEm == null }

        liberar("pin-admin@x.com").andExpect(status().isOk).andExpect(jsonPath("$.status").value("ok"))

        assertThat(sessoes.findAll().count { it.encerradaEm == null }).isEqualTo(abertasAntes)
    }

    @Test
    fun `quem nao e admin nao libera mesmo com a senha certa`() {
        operadores.criarUsuario(email = "pin-gestor@x.com", role = Role.GESTOR)

        liberar("pin-gestor@x.com")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.mensagem").value("Só um administrador pode liberar o PIN das Configurações do coletor."))
    }

    @Test
    fun `senha errada e recusada e conta para o bloqueio`() {
        operadores.criarUsuario(email = "pin-admin2@x.com", role = Role.ADMIN)

        repeat(5) { liberar("pin-admin2@x.com", senha = "errada").andExpect(status().isUnauthorized) }

        // Bloqueado: nem a senha certa passa
        liberar("pin-admin2@x.com").andExpect(status().isTooManyRequests)
    }

    @Test
    fun `sem a chave do coletor nao chega a conferir nada`() {
        operadores.criarUsuario(email = "pin-admin3@x.com", role = Role.ADMIN)

        liberar("pin-admin3@x.com", chave = "chave-errada").andExpect(status().isUnauthorized)
    }
}
