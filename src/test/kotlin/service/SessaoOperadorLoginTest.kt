package org.example.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.repository.SessaoOperadorRepository
import org.example.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Duration

class SessaoOperadorLoginTest {

    private val usuarios = mockk<UserRepository>()
    private val senhas = mockk<PasswordEncoder>()
    private val tentativas = mockk<TentativasLoginService>(relaxed = true)

    private val service = SessaoOperadorService(
        sessoes = mockk<SessaoOperadorRepository>(relaxed = true), usuarios = usuarios,
        detalhes = mockk(relaxed = true), senhas = senhas, tentativas = tentativas,
        politicaSenha = mockk(relaxed = true), cadastroColetor = mockk(relaxed = true),
        duracao = Duration.ofHours(12), janelaSincronizacao = Duration.ofHours(72), relogio = Clock.systemDefaultZone()
    )

    @Test
    fun `email inexistente tambem passa pelo BCrypt (o tempo de resposta nao revela quais emails existem)`() {
        every { tentativas.tempoRestanteDeBloqueio(any(), any()) } returns null
        every { usuarios.findByEmailIgnoreCase("ninguem@x.com") } returns null
        every { senhas.encode(any()) } returns "\$2a\$10\$hash-ficticio"
        every { senhas.matches(any(), any()) } returns false

        assertThatThrownBy { service.entrar("ninguem@x.com", "Qualquer123", "coletor-01", "10.0.0.1") }
            .isInstanceOf(LoginRecusadoException::class.java)
            .hasMessage(SessaoOperadorService.MENSAGEM_CREDENCIAIS)

        // Antes o BCrypt só rodava para email existente, e a resposta do inexistente voltava dezenas de ms antes
        verify { senhas.matches("Qualquer123", "\$2a\$10\$hash-ficticio") }
        verify { tentativas.registrarFalha("ninguem@x.com", "coletor-01@10.0.0.1") }
    }
}
