package org.example.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MascaraSensivelTest {

    @Test
    fun `mascara headers da API do coletor`() {
        assertThat(MascaraSensivel.limpar("header X-Sessao-Operador: abc123 enviado"))
            .isEqualTo("header X-Sessao-Operador: *** enviado")
        assertThat(MascaraSensivel.limpar("X-Chave-Dispositivo=minha-chave"))
            .isEqualTo("X-Chave-Dispositivo=***")
    }

    @Test
    fun `mascara senha token e chave em pares nome valor`() {
        assertThat(MascaraSensivel.limpar("login falhou: senha=Kx9mPq2v, email=ana@x.com"))
            .isEqualTo("login falhou: senha=***, email=ana@x.com")
        assertThat(MascaraSensivel.limpar("token: abc  password=x  Bearer qwe"))
            .isEqualTo("token: ***  password=***  Bearer ***")
    }

    @Test
    fun `mascara sequencias longas que parecem tokens e mantem o resto do stack`() {
        val stack = "java.lang.IllegalStateException: sessão tE3n0kLm9QpR2sT4uV5wX6yZ7aB8cD9eF0gH1iJ2kL3m inválida\n" +
            "\tat com.example.rfid_server.service.TagService.entrar(TagService.kt:42)"

        val limpo = MascaraSensivel.limpar(stack)

        assertThat(limpo).contains("sessão *** inválida")
        assertThat(limpo).contains("at com.example.rfid_server.service.TagService.entrar(TagService.kt:42)")
    }
}
