package org.example.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PoliticaSenhaTest {

    private val politica = PoliticaSenha(tamanhoMinimo = 8)

    @ParameterizedTest
    @ValueSource(strings = ["Kx9mPq2v", "estoque2026verde", "Ponte#Azul7", "çãoÁgua9xyz"])
    fun `senhas boas sao aceitas`(senha: String) {
        assertThat(politica.problemas(senha, "ana@empresa.com", "Ana Souza")).isEmpty()
    }

    @Test
    fun `curta demais`() {
        assertThat(politica.problemas("Ab1")).contains("a senha deve ter pelo menos 8 caracteres")
    }

    @Test
    fun `tamanho minimo e configuravel`() {
        assertThat(PoliticaSenha(tamanhoMinimo = 12).problemas("Kx9mPq2v")).contains("a senha deve ter pelo menos 12 caracteres")
    }

    @Test
    fun `so numeros ou so letras nao servem`() {
        assertThat(politica.problemas("98765432")).contains("a senha deve ter letras e números")
        assertThat(politica.problemas("abcdefghij")).contains("a senha deve ter letras e números")
    }

    @Test
    fun `mais de 72 bytes e recusada (o BCrypt cortaria o resto sem avisar)`() {
        assertThat(politica.problemas("a1".repeat(37))).contains("a senha pode ter no máximo 72 caracteres")
        // acentos ocupam 2 bytes: 51 caracteres (25 com acento) já passam de 72 bytes
        assertThat(politica.problemas("á1".repeat(25) + "x")).contains("a senha pode ter no máximo 72 caracteres")
    }

    @Test
    fun `nao pode conter o email`() {
        assertThat(politica.problemas("Gabriel2026x", email = "gabriel@empresa.com"))
            .contains("a senha não pode conter o seu email")
    }

    @Test
    fun `nao pode conter partes do nome`() {
        assertThat(politica.problemas("souza2026xy", nome = "Ana Souza")).contains("a senha não pode conter o seu nome")
    }

    @Test
    fun `partes curtas do nome e do email nao contam`() {
        // Partes com menos de 3 letras ("Ed", "Li", "jo") não contam: pegariam senhas boas por acaso
        assertThat(politica.problemas("Edlijo2026x", email = "jo@x.com", nome = "Ed Li")).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["senha123", "Senha1234", "admin123", "1q2w3e4r", "Password1", "mudar123"])
    fun `senhas comuns sao recusadas, ignorando maiusculas`(senha: String) {
        assertThat(politica.problemas(senha)).contains("essa senha é muito comum e fácil de adivinhar")
    }

    @Test
    fun `senha com um ou dois caracteres repetidos e recusada`() {
        assertThat(politica.problemas("a1a1a1a1a1")).contains("essa senha é muito comum e fácil de adivinhar")
    }

    @Test
    fun `exigir junta todos os motivos numa mensagem`() {
        assertThatThrownBy { politica.exigir("abc", contexto = "teste") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Senha fraca: a senha deve ter pelo menos 8 caracteres; a senha deve ter letras e números.")
    }

    @Test
    fun `descricao das regras usa o tamanho configurado`() {
        assertThat(PoliticaSenha(tamanhoMinimo = 10).descricao).startsWith("Mínimo de 10 caracteres")
    }
}
