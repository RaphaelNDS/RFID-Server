package org.example.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.Acao
import org.example.model.Modulo
import org.example.model.Role
import org.example.model.UsuarioEntity
import org.example.repository.UserRepository
import org.example.request.CadastroUsuarioRequest
import org.example.request.SetupAdminRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

class UsuarioServiceTest {

    private val repo = mockk<UserRepository>(relaxed = true)

    // Encoder previsível: "hash(" + senha + ")"
    private val encoder = object : PasswordEncoder {
        override fun encode(raw: CharSequence) = "hash($raw)"
        override fun matches(raw: CharSequence, encoded: String) = encoded == "hash($raw)"
    }

    private val service = UsuarioService(repo, encoder, PoliticaSenha(tamanhoMinimo = 8))

    private val salvos = mutableListOf<UsuarioEntity>()

    @BeforeEach
    fun configurar() {
        salvos.clear()
        every { repo.save(any<UsuarioEntity>()) } answers { firstArg<UsuarioEntity>().also { salvos += it } }
        every { repo.count() } returns 1
        every { repo.existsByEmailIgnoreCase(any()) } returns false
    }

    private fun usuarioExistente(id: Long = 7, email: String = "ana@x.com", senha: String = "123") =
        UsuarioEntity(id = id, nome = "Ana", email = email, senha = "hash($senha)", role = Role.USER)
            .also { every { repo.findById(id) } returns Optional.of(it) }

    private fun req(role: Role, telas: List<String> = emptyList(), permissoes: List<String> = emptyList(), email: String = "novo@x.com") =
        CadastroUsuarioRequest(nome = "Novo", email = email, senha = "Kx9mPq2v", role = role, telas = telas, permissoes = permissoes)

    private fun permissoesDoUltimoSalvo() = salvos.last().permissoes.map { "${it.modulo}_${it.acao}" }.toSet()

    @Nested
    inner class AdminInicial {

        @Test
        fun `cria admin com todas as permissoes e senha criptografada`() {
            every { repo.count() } returns 0

            service.criarAdminInicial(SetupAdminRequest("Ana", "ana@x.com", "Kx9mPq2v"))

            val admin = salvos.last()
            assertThat(admin.role).isEqualTo(Role.ADMIN)
            assertThat(admin.senha).isEqualTo("hash(Kx9mPq2v)")
            assertThat(admin.permissoes).hasSize(Modulo.values().size * Acao.values().size)
        }

        @Test
        fun `senha fraca no setup e recusada sem gravar`() {
            every { repo.count() } returns 0

            assertThatThrownBy { service.criarAdminInicial(SetupAdminRequest("Ana", "ana@x.com", "123")) }
                .isInstanceOf(IllegalArgumentException::class.java)

            verify(exactly = 0) { repo.save(any<UsuarioEntity>()) }
        }

        @Test
        fun `recusa quando ja existe usuario (protecao do setup)`() {
            every { repo.count() } returns 1

            assertThatThrownBy { service.criarAdminInicial(SetupAdminRequest("Hacker", "h@x.com", "123")) }
                .isInstanceOf(IllegalStateException::class.java)

            verify(exactly = 0) { repo.save(any<UsuarioEntity>()) }
        }
    }

    @Nested
    inner class Cadastro {

        @Test
        fun `email repetido e recusado sem gravar nada`() {
            every { repo.existsByEmailIgnoreCase("MARIA@x.com") } returns true

            assertThatThrownBy { service.cadastrar(req(Role.USER, email = "MARIA@x.com")) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Já existe um usuário com este email")

            verify(exactly = 0) { repo.save(any<UsuarioEntity>()) }
        }

        @Test
        fun `senha fraca no cadastro e recusada sem gravar`() {
            val fraca = CadastroUsuarioRequest(nome = "Novo", email = "novo@x.com", senha = "12345678", role = Role.USER)

            assertThatThrownBy { service.cadastrar(fraca) }.hasMessageContaining("letras e números")

            verify(exactly = 0) { repo.save(any<UsuarioEntity>()) }
        }

        @Test
        fun `primeiro usuario do sistema vira ADMIN mesmo pedindo USER`() {
            every { repo.count() } returns 0

            service.cadastrar(req(Role.USER))

            assertThat(salvos.last().role).isEqualTo(Role.ADMIN)
        }

        @Test
        fun `USER recebe so leitura em todos os modulos`() {
            service.cadastrar(req(Role.USER))

            assertThat(permissoesDoUltimoSalvo()).containsExactlyInAnyOrderElementsOf(Modulo.values().map { "${it}_READ" })
        }

        @Test
        fun `GESTOR le, cria e edita, mas nao exclui`() {
            service.cadastrar(req(Role.GESTOR))

            val permissoes = permissoesDoUltimoSalvo()
            assertThat(permissoes).hasSize(Modulo.values().size * 3)
            assertThat(permissoes).noneMatch { it.endsWith("_DELETE") }
            assertThat(permissoes).contains("RFID_CREATE", "CATALOGO_UPDATE")
        }

        @Test
        fun `CUSTOM recebe exatamente a combinacao escolhida`() {
            service.cadastrar(req(Role.CUSTOM, telas = listOf("CATALOGO", "LEITURA"), permissoes = listOf("READ", "CREATE")))

            assertThat(permissoesDoUltimoSalvo())
                .containsExactlyInAnyOrder("CATALOGO_READ", "CATALOGO_CREATE", "LEITURA_READ", "LEITURA_CREATE")
        }

        @Test
        fun `CUSTOM sem modulo e recusado antes de gravar`() {
            assertThatThrownBy { service.cadastrar(req(Role.CUSTOM, telas = emptyList(), permissoes = listOf("READ"))) }
                .hasMessage("Selecione pelo menos um módulo")

            verify(exactly = 0) { repo.save(any<UsuarioEntity>()) }
        }

        @Test
        fun `CUSTOM sem acao e recusado antes de gravar`() {
            assertThatThrownBy { service.cadastrar(req(Role.CUSTOM, telas = listOf("CATALOGO"), permissoes = emptyList())) }
                .hasMessage("Selecione pelo menos uma permissão")

            verify(exactly = 0) { repo.save(any<UsuarioEntity>()) }
        }

        @Test
        fun `CUSTOM com modulo inexistente e recusado antes de gravar`() {
            // Antes, o formulario enviava "TAG" e sobrava um usuario sem permissoes no banco
            assertThatThrownBy { service.cadastrar(req(Role.CUSTOM, telas = listOf("TAG"), permissoes = listOf("READ"))) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Módulo inválido: TAG")

            verify(exactly = 0) { repo.save(any<UsuarioEntity>()) }
        }
    }

    @Nested
    inner class TrocaDeSenha {

        @Test
        fun `senha atual errada e recusada`() {
            usuarioExistente(senha = "123")

            assertThatThrownBy { service.alterarSenha(7, "errada", "nova", "nova") }
                .hasMessage("Senha atual incorreta")
        }

        @Test
        fun `confirmacao diferente e recusada`() {
            usuarioExistente(senha = "123")

            assertThatThrownBy { service.alterarSenha(7, "123", "nova", "outra") }
                .hasMessage("A nova senha e a confirmação não conferem")
        }

        @Test
        fun `troca a senha gravando o hash novo`() {
            val usuario = usuarioExistente(senha = "123")

            service.alterarSenha(7, "123", "Zt7wLn4r", "Zt7wLn4r")

            assertThat(usuario.senha).isEqualTo("hash(Zt7wLn4r)")
            verify { repo.save(usuario) }
        }

        @Test
        fun `nova senha fraca e recusada sem gravar`() {
            val usuario = usuarioExistente(senha = "123")

            assertThatThrownBy { service.alterarSenha(7, "123", "abc", "abc") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageStartingWith("Senha fraca:")

            assertThat(usuario.senha).isEqualTo("hash(123)")
        }

        @Test
        fun `nova senha igual a atual e recusada`() {
            usuarioExistente(senha = "Kx9mPq2v")

            assertThatThrownBy { service.alterarSenha(7, "Kx9mPq2v", "Kx9mPq2v", "Kx9mPq2v") }
                .hasMessage("A nova senha deve ser diferente da atual")
        }

        @Test
        fun `usuario que nao existe mais gera erro de estado`() {
            every { repo.findById(99) } returns Optional.empty()

            assertThatThrownBy { service.alterarSenha(99, "a", "b", "b") }
                .isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Nested
    inner class TrocaDeEmail {

        @Test
        fun `exige a senha atual`() {
            usuarioExistente()

            assertThatThrownBy { service.alterarEmail(7, "nova@x.com", "errada") }
                .hasMessage("Senha atual incorreta")
        }

        @Test
        fun `formato invalido e recusado`() {
            usuarioExistente()

            assertThatThrownBy { service.alterarEmail(7, "invalido", "123") }.hasMessage("Email inválido")
        }

        @Test
        fun `igual ao atual, mesmo mudando maiusculas, e recusado`() {
            usuarioExistente(email = "ana@x.com")

            assertThatThrownBy { service.alterarEmail(7, "ANA@x.com", "123") }.hasMessage("O novo email é igual ao atual")
        }

        @Test
        fun `email de outro usuario e recusado`() {
            usuarioExistente()
            every { repo.existsByEmailIgnoreCase("bruno@x.com") } returns true

            assertThatThrownBy { service.alterarEmail(7, "bruno@x.com", "123") }
                .hasMessage("Este email já está em uso por outro usuário")
        }

        @Test
        fun `troca o email removendo espacos e devolve o valor gravado`() {
            val usuario = usuarioExistente()

            val gravado = service.alterarEmail(7, "  nova@x.com  ", "123")

            assertThat(gravado).isEqualTo("nova@x.com")
            assertThat(usuario.email).isEqualTo("nova@x.com")
        }
    }
}
