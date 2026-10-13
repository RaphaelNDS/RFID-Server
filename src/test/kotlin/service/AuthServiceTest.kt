package org.example.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class AuthServiceTest {

    private val auth = AuthService()

    @AfterEach
    fun limparContexto() = SecurityContextHolder.clearContext()

    private fun logado(vararg autoridades: String) {
        SecurityContextHolder.getContext().authentication =
            TestingAuthenticationToken("usuario@x.com", null, *autoridades)
    }

    @Test
    fun `sem ninguem logado nao tem permissao nenhuma`() {
        assertThat(auth.temPermissao("CATALOGO", "READ")).isFalse()
        assertThat(auth.temRole("ADMIN")).isFalse()
        assertThat(auth.isAdmin()).isFalse()
    }

    @Test
    fun `admin passa em qualquer permissao`() {
        logado("ROLE_ADMIN")

        assertThat(auth.temPermissao("CATALOGO", "DELETE")).isTrue()
        assertThat(auth.temPermissao("USUARIO", "CREATE")).isTrue()
        assertThat(auth.isAdmin()).isTrue()
    }

    @Test
    fun `usuario comum so passa nas permissoes que tem`() {
        logado("ROLE_USER", "CATALOGO_READ", "RFID_READ")

        assertThat(auth.temPermissao("CATALOGO", "READ")).isTrue()
        assertThat(auth.temPermissao("RFID", "READ")).isTrue()
        assertThat(auth.temPermissao("CATALOGO", "CREATE")).isFalse()
        assertThat(auth.temPermissao("LEITURA", "READ")).isFalse()
    }

    @Test
    fun `modulo inexistente nunca libera usuario comum`() {
        // Foi o bug do 'TAG': o modulo nao existe no enum, entao nenhuma permissao gravada no banco
        // corresponde a ele e so o ADMIN passava. Aqui: gestor com TODAS as permissoes reais.
        val todasReais = org.example.model.Modulo.values().flatMap { m ->
            org.example.model.Acao.values().map { a -> "${m}_$a" }
        }
        logado("ROLE_GESTOR", *todasReais.toTypedArray())

        assertThat(auth.temPermissao("TAG", "READ")).isFalse()
        assertThat(auth.temPermissao("RFID", "READ")).isTrue()
    }

    @Test
    fun `temRole compara com o prefixo ROLE_`() {
        logado("ROLE_GESTOR")

        assertThat(auth.temRole("GESTOR")).isTrue()
        assertThat(auth.temRole("ADMIN")).isFalse()
    }

    @Test
    fun `temAlgumaPermissao e temTodasPermissoes`() {
        logado("ROLE_USER", "CATALOGO_READ")

        assertThat(auth.temAlgumaPermissao("CATALOGO_READ", "RFID_CREATE")).isTrue()
        assertThat(auth.temTodasPermissoes("CATALOGO_READ", "RFID_CREATE")).isFalse()
        assertThat(auth.temTodasPermissoes("CATALOGO_READ")).isTrue()
    }
}
