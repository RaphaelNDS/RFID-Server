package org.example.integracao

import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Só nos testes: diz se a sessão do JPA ficou presa à requisição ("open in view") enquanto o controller roda.
 * Uma rota na API dos coletores e outra numa tela (/admin), para comparar.
 */
@RestController
class SessaoNaRequisicaoTesteController(private val emf: EntityManagerFactory) {
    @org.springframework.security.access.prepost.PreAuthorize("@authService.temPermissao('LEITURA','READ')")
    @GetMapping("/api/android/teste-sessao-requisicao")
    fun naApi() = mapOf("presa" to TransactionSynchronizationManager.hasResource(emf))

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/teste-sessao-requisicao")
    fun naTela() = mapOf("presa" to TransactionSynchronizationManager.hasResource(emf))
}

/**
 * 1.5.2: a API dos coletores não segura uma conexão do banco do começo ao fim da requisição (open in view só nas telas).
 * Fora da transação dos outros testes de integração: a transação do teste prenderia a sessão nas duas rotas.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class Versao152IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    @Test
    fun `api dos coletores roda sem a sessao do JPA presa e as telas com ela`() {
        val email = "v152-${java.util.UUID.randomUUID()}@x.com"
        operadores.criarUsuario(email = email)
        try {
            val token = operadores.entrar(mvc, email)
            mvc.perform(get("/api/android/teste-sessao-requisicao").comoOperador(token))
                .andExpect(status().isOk)
                .andExpect(content().json("""{"presa":false}"""))
            mvc.perform(get("/admin/teste-sessao-requisicao").with(Perfis.admin()))
                .andExpect(status().isOk)
                .andExpect(content().json("""{"presa":true}"""))
        } finally {
            jdbc.update("delete from permissoes where usuario_id in (select id from usuarios where email = ?)", email)
            jdbc.update("delete from usuarios where email = ?", email)
            jdbc.update("delete from coletores where nome = ?", "coletor-teste")
        }
    }

    @Test
    fun `tela que le colecao lazy continua abrindo sem a transacao do teste`() {
        // Tela de usuários: lê as permissões (lazy) ao montar o HTML, e precisa da sessão na requisição
        mvc.perform(get("/admin/usuarios").with(Perfis.admin())).andExpect(status().isOk)
    }

    @Test
    fun `pool de conexoes maior que o padrao`(@Autowired ds: javax.sql.DataSource) {
        assertThat((ds as com.zaxxer.hikari.HikariDataSource).maximumPoolSize).isEqualTo(30)
    }
}
