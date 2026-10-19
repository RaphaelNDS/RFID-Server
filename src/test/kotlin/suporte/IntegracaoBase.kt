package org.example.suporte

import org.example.model.Acao
import org.example.model.Modulo
import org.example.security.UserPrincipal
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.annotation.Transactional

/**
 * Base dos testes de integração: aplicação completa (segurança, controllers, templates, PostgreSQL num
 * contêiner temporário com as migrações do Flyway). Cada teste roda numa transação desfeita no final, então um não vê os dados do outro.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
abstract class IntegracaoBase {

    @Autowired
    lateinit var mvc: MockMvc

    companion object {
        const val CHAVE_COLETOR = "chave-de-teste-0123456789" // mesma de application-test.yml
    }
}

/** Usuários logados simulados, com as mesmas autoridades que o login real monta. */
object Perfis {

    private fun todas(vararg acoes: Acao) = Modulo.values().flatMap { m -> acoes.map { a -> "${m}_$a" } }

    fun admin(id: Long = 1) = logado(id, "Ana Admin", "admin@x.com", "ADMIN", todas(*Acao.values()))
    fun gestor(id: Long = 2) = logado(id, "Gabriel Gestor", "gestor@x.com", "GESTOR", todas(Acao.READ, Acao.CREATE, Acao.UPDATE))
    fun usuario(id: Long = 3) = logado(id, "Úrsula Usuária", "user@x.com", "USER", todas(Acao.READ))
    fun somenteCatalogo(id: Long = 4) = logado(id, "Carla Custom", "carla@x.com", "CUSTOM", listOf("CATALOGO_READ"))

    fun porNome(nome: String): RequestPostProcessor = when (nome) {
        "ADMIN" -> admin()
        "GESTOR" -> gestor()
        "USER" -> usuario()
        "CUSTOM" -> somenteCatalogo()
        else -> error("perfil desconhecido: $nome")
    }

    fun logado(id: Long, nome: String, email: String, role: String, permissoes: List<String>): RequestPostProcessor {
        val autoridades = (listOf("ROLE_$role") + permissoes).map { SimpleGrantedAuthority(it) }
        return user(UserPrincipal(id, nome, email, "hash", autoridades))
    }
}
