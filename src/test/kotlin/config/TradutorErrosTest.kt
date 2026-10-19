package org.example.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException

class TradutorErrosTest {

    private val request = MockHttpServletRequest("POST", "/api/teste")

    private fun traduzir(ex: Exception, enviada: Boolean = false) = TradutorErros.traduzir(ex, request, enviada)

    @Test
    fun `erro de validacao vira 400 com a mensagem original`() {
        val erro = traduzir(IllegalArgumentException("Esta etiqueta já está cadastrada"))

        assertThat(erro.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(erro.mensagem).isEqualTo("Esta etiqueta já está cadastrada")
        assertThat(erro.codigo).isNull()
    }

    @Test
    fun `conflito de estado vira 409`() {
        val erro = traduzir(IllegalStateException("Não é possível excluir tipo com marcas vinculadas"))

        assertThat(erro.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(erro.mensagem).isEqualTo("Não é possível excluir tipo com marcas vinculadas")
    }

    @Test
    fun `violacao de integridade do banco vira 409 sem expor detalhes do SQL`() {
        val erro = traduzir(DataIntegrityViolationException("Unique index or primary key violation: PUBLIC.TAGS(TAG_REAL)"))

        assertThat(erro.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(erro.mensagem).doesNotContain("PUBLIC").contains("Registro duplicado")
    }

    @Test
    fun `data mal formatada vira 400 explicando o formato`() {
        val ex = assertThatThrownByAndGet { LocalDate.parse("abc") } as DateTimeParseException

        val erro = traduzir(ex)

        assertThat(erro.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(erro.mensagem).contains("'abc'").contains("AAAA-MM-DD")
    }

    @Test
    fun `registro inexistente vira 404`() {
        assertThat(traduzir(NoSuchElementException()).status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `erro inesperado vira 500 com codigo de suporte`() {
        val erro = traduzir(NullPointerException("boom"))

        assertThat(erro.status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(erro.codigo).hasSize(8)
        assertThat(erro.mensagem).doesNotContain("boom") // detalhe técnico fica só no log
    }

    @Test
    fun `cada erro inesperado ganha um codigo diferente`() {
        assertThat(traduzir(RuntimeException()).codigo).isNotEqualTo(traduzir(RuntimeException()).codigo)
    }

    @Test
    fun `acesso negado e falta de login ficam com o Spring Security`() {
        // Se virassem 500, um 403 legítimo apareceria como erro do servidor
        assertThatThrownBy { traduzir(AccessDeniedException("negado")) }.isInstanceOf(AccessDeniedException::class.java)
        assertThatThrownBy { traduzir(BadCredentialsException("x")) }.isInstanceOf(BadCredentialsException::class.java)
    }

    @Test
    fun `cliente que desconectou ou resposta ja enviada nao geram pagina de erro`() {
        assertThatThrownBy { traduzir(IOException("Broken pipe")) }.isInstanceOf(IOException::class.java)
        assertThatThrownBy { traduzir(IllegalArgumentException("x"), enviada = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `conexao fechada pelo navegador e reconhecida e outros erros nao`() {
        assertThat(TradutorErros.clienteDesconectou(IOException("Broken pipe"), request)).isTrue()
        assertThat(TradutorErros.clienteDesconectou(IllegalStateException("x"), request)).isFalse()
    }

    @Test
    fun `api encerra sem resposta quando o cliente desconectou`() {
        val response = MockHttpServletResponse()

        assertThat(TratamentoErrosApi().tratar(IOException("Uma conexão estabelecida foi anulada"), request, response)).isNull()
    }

    @Test
    fun `tela encerra sem pagina de erro quando o cliente desconectou`() {
        val mav = TratamentoErros().tratar(IOException("Broken pipe"), request, MockHttpServletResponse())

        assertThat(mav.viewName).isNull()
        assertThat(mav.model).isEmpty()
    }

    private fun assertThatThrownByAndGet(bloco: () -> Unit): Throwable =
        runCatching(bloco).exceptionOrNull() ?: error("era esperado um erro")
}
