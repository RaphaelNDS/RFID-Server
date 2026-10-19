package org.example.suporte

import com.jayway.jsonpath.JsonPath
import org.example.model.Acao
import org.example.model.Modulo
import org.example.model.PermissaoEntity
import org.example.model.Role
import org.example.model.UsuarioEntity
import org.example.repository.UserRepository
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Operador logado no coletor, para testes da API /api/android (que exige login de operador).
 * Cria o usuário com as permissões pedidas e faz o login de verdade pelo POST /api/android/login.
 */
@Component
class OperadorTeste(
    private val usuarios: UserRepository,
    private val senhas: PasswordEncoder
) {

    companion object {
        const val SENHA = "Kx9mPq2v" // atende à política de senha
        const val HEADER_CHAVE = "X-Chave-Dispositivo"
        const val HEADER_SESSAO = "X-Sessao-Operador"
    }

    fun criarUsuario(
        email: String = "operador@x.com",
        nome: String = "Olga Operadora",
        role: Role = Role.CUSTOM,
        vararg permissoes: Pair<Modulo, Acao> = arrayOf(Modulo.LEITURA to Acao.READ)
    ): UsuarioEntity {
        val usuario = UsuarioEntity(nome = nome, email = email, senha = senhas.encode(SENHA), role = role)
        permissoes.forEach { (modulo, acao) -> usuario.permissoes += PermissaoEntity(modulo = modulo, acao = acao, usuario = usuario) }
        return usuarios.save(usuario)
    }

    /** Faz login no coletor e devolve o token da sessão. */
    fun entrar(mvc: MockMvc, email: String, chave: String = IntegracaoBase.CHAVE_COLETOR, senha: String = SENHA): String {
        val resposta = mvc.perform(
            post("/api/android/login").header(HEADER_CHAVE, chave)
                .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","senha":"$senha"}""")
        ).andReturn().response

        check(resposta.status == 200) { "Login do operador falhou no teste: ${resposta.status} ${resposta.contentAsString}" }
        return JsonPath.read(resposta.contentAsString, "$.token")
    }

    /** Cria um operador só com Leituras e devolve o token já logado no coletor-teste. */
    fun sessaoPadrao(mvc: MockMvc, email: String = "operador@x.com"): String {
        if (usuarios.findByEmail(email) == null) criarUsuario(email = email)
        return entrar(mvc, email)
    }
}

/** Adiciona a chave do coletor e a sessão do operador à requisição. */
fun MockHttpServletRequestBuilder.comoOperador(token: String, chave: String = IntegracaoBase.CHAVE_COLETOR): MockHttpServletRequestBuilder =
    header(OperadorTeste.HEADER_CHAVE, chave).header(OperadorTeste.HEADER_SESSAO, token)
