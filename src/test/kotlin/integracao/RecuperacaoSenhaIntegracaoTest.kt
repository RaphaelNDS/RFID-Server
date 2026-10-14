package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.Role
import org.example.model.UsuarioEntity
import org.example.repository.UserRepository
import org.example.service.PoliticaSenha
import org.example.service.RecuperacaoSenhaService
import org.example.service.TentativasLoginService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.concurrent.atomic.AtomicInteger

class RecuperacaoSenhaIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var usuarios: UserRepository
    @Autowired lateinit var encoder: PasswordEncoder
    @Autowired lateinit var recuperacao: RecuperacaoSenhaService
    @Autowired lateinit var politicaSenha: PoliticaSenha
    @Autowired lateinit var tentativasLogin: TentativasLoginService

    private lateinit var bruno: UsuarioEntity

    // O limite de pedidos por IP fica em memória e vale para o contexto inteiro: cada teste usa IPs próprios
    private val ip = "10.77.${proximoIp.incrementAndGet()}.1"

    @BeforeEach
    fun criarUsuario() {
        bruno = usuarios.save(UsuarioEntity(nome = "Bruno Souza", email = "bruno@x.com", senha = encoder.encode("Kx9mPq2v"), role = Role.USER))
    }

    private fun pedir(email: String, deIp: String = ip) =
        mvc.perform(post("/esqueci-senha").with(csrf()).with { it.remoteAddr = deIp; it }.param("email", email))

    private fun recarregar() = usuarios.findById(bruno.id!!).get()

    @Nested
    inner class Pedido {

        @Test
        fun `a tela de login tem o link e a tela do pedido abre sem login`() {
            mvc.perform(get("/login")).andExpect(content().string(containsString("Esqueceu a senha?")))
            mvc.perform(get("/esqueci-senha"))
                .andExpect(status().isOk)
                .andExpect(view().name("esqueci-senha"))
        }

        @Test
        fun `pedido de email cadastrado fica registrado para o admin`() {
            pedir("Bruno@X.com ").andExpect(redirectedUrl("/login?pedidoSenha"))

            assertThat(recarregar().senhaPedidaEm).isNotNull()
            assertThat(recuperacao.quantidadePendentes()).isEqualTo(1)
        }

        @Test
        fun `email nao cadastrado recebe a mesma resposta e nada e gravado`() {
            pedir("ninguem@x.com").andExpect(redirectedUrl("/login?pedidoSenha"))

            assertThat(recuperacao.quantidadePendentes()).isZero()
        }

        @Test
        fun `pedir de novo nao cria outro pedido nem muda a data`() {
            pedir("bruno@x.com")
            val primeiro = recarregar().senhaPedidaEm

            pedir("bruno@x.com")

            assertThat(recarregar().senhaPedidaEm).isEqualTo(primeiro)
            assertThat(recuperacao.quantidadePendentes()).isEqualTo(1)
        }

        @Test
        fun `o mesmo IP nao pode pedir sem limite`() {
            repeat(RecuperacaoSenhaService.MAX_PEDIDOS_POR_IP) { pedir("outro$it@x.com") }

            pedir("bruno@x.com").andExpect(redirectedUrl("/login?pedidoSenha")) // resposta igual, mas ignorado

            assertThat(recarregar().senhaPedidaEm).isNull()
            pedir("bruno@x.com", deIp = "$ip-outro")
            assertThat(recarregar().senhaPedidaEm).isNotNull()
        }

        @Test
        fun `pedido sem token CSRF e bloqueado`() {
            mvc.perform(post("/esqueci-senha").param("email", "bruno@x.com")).andExpect(status().isForbidden)
            assertThat(recarregar().senhaPedidaEm).isNull()
        }
    }

    @Nested
    inner class Administrador {

        @Test
        fun `admin ve o pedido na tela de usuarios e o contador no menu`() {
            pedir("bruno@x.com")

            mvc.perform(get("/admin/usuarios").with(Perfis.admin()))
                .andExpect(content().string(containsString("Pedidos de nova senha")))
                .andExpect(content().string(containsString("Gerar senha temporária")))
                .andExpect(content().string(containsString("class=\"contador\"")))
        }

        @Test
        fun `sem pedidos a tela nao mostra o quadro nem o contador`() {
            mvc.perform(get("/admin/usuarios").with(Perfis.admin()))
                .andExpect(content().string(not(containsString("Pedidos de nova senha"))))
                .andExpect(content().string(not(containsString("class=\"contador\""))))
        }

        @Test
        fun `gerar senha temporaria mostra a senha uma vez e fecha o pedido`() {
            pedir("bruno@x.com")

            val resultado = mvc.perform(post("/admin/usuarios/${bruno.id}/senha-temporaria").with(Perfis.admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/usuarios"))
                .andReturn()

            val gerada = resultado.flashMap["senhaTemporaria"] as RecuperacaoSenhaService.SenhaTemporaria
            val salvo = recarregar()
            assertThat(encoder.matches(gerada.senha, salvo.senha)).isTrue()
            assertThat(encoder.matches("Kx9mPq2v", salvo.senha)).isFalse() // a senha antiga deixou de valer
            assertThat(salvo.trocarSenha).isTrue()
            assertThat(salvo.senhaPedidaEm).isNull()
            assertThat(politicaSenha.problemas(gerada.senha, salvo.email, salvo.nome)).isEmpty()
        }

        @Test
        fun `gerar senha temporaria libera o login bloqueado por tentativas erradas`() {
            repeat(10) { tentativasLogin.registrarFalha("bruno@x.com", "10.8.8.8") }
            assertThat(tentativasLogin.tempoRestanteDeBloqueio("bruno@x.com", null)).isNotNull()

            recuperacao.gerarSenhaTemporaria(bruno.id!!)

            assertThat(tentativasLogin.tempoRestanteDeBloqueio("bruno@x.com", null)).isNull()
        }

        @Test
        fun `descartar fecha o pedido sem mudar a senha`() {
            pedir("bruno@x.com")

            mvc.perform(post("/admin/usuarios/${bruno.id}/descartar-pedido").with(Perfis.admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/usuarios"))

            val salvo = recarregar()
            assertThat(salvo.senhaPedidaEm).isNull()
            assertThat(encoder.matches("Kx9mPq2v", salvo.senha)).isTrue()
        }

        @Test
        fun `quem nao e admin nao gera senha de ninguem`() {
            mvc.perform(post("/admin/usuarios/${bruno.id}/senha-temporaria").with(Perfis.gestor()).with(csrf()))
                .andExpect(status().isForbidden)

            assertThat(encoder.matches("Kx9mPq2v", recarregar().senha)).isTrue()
        }
    }

    @Nested
    inner class SenhaTemporaria {

        private fun entrar(senha: String): MockHttpSession {
            val resultado = mvc.perform(post("/login").with(csrf()).with { it.remoteAddr = ip; it }
                .param("username", "bruno@x.com").param("password", senha))
                .andExpect(redirectedUrl("/perfil/senha?temporaria"))
                .andReturn()
            return resultado.request.session as MockHttpSession
        }

        @Test
        fun `quem entra com senha temporaria so acessa a troca de senha`() {
            val temporaria = recuperacao.gerarSenhaTemporaria(bruno.id!!).senha
            val sessao = entrar(temporaria)

            mvc.perform(get("/dashboard").session(sessao)).andExpect(redirectedUrl("/perfil/senha?temporaria"))
            mvc.perform(get("/perfil").session(sessao)).andExpect(redirectedUrl("/perfil/senha?temporaria"))
            mvc.perform(get("/perfil/senha").session(sessao))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("senha temporária")))
        }

        @Test
        fun `depois de criar a senha nova o sistema fica liberado`() {
            val temporaria = recuperacao.gerarSenhaTemporaria(bruno.id!!).senha
            val sessao = entrar(temporaria)

            mvc.perform(post("/perfil/senha").session(sessao).with(csrf())
                .param("senhaAtual", temporaria).param("novaSenha", "Nova2senhaQ").param("confirmarSenha", "Nova2senhaQ"))
                .andExpect(redirectedUrl("/perfil?senhaAlterada"))

            mvc.perform(get("/dashboard").session(sessao)).andExpect(status().isOk)
            val salvo = recarregar()
            assertThat(salvo.trocarSenha).isFalse()
            assertThat(encoder.matches("Nova2senhaQ", salvo.senha)).isTrue()
        }

        @Test
        fun `trocar a propria senha encerra o pedido em aberto`() {
            pedir("bruno@x.com")

            mvc.perform(post("/perfil/senha").with(Perfis.logado(bruno.id!!, "Bruno Souza", "bruno@x.com", "USER", emptyList())).with(csrf())
                .param("senhaAtual", "Kx9mPq2v").param("novaSenha", "Nova2senhaQ").param("confirmarSenha", "Nova2senhaQ"))
                .andExpect(redirectedUrl("/perfil?senhaAlterada"))

            assertThat(recarregar().senhaPedidaEm).isNull()
        }
    }

    companion object {
        private val proximoIp = AtomicInteger()
    }
}
