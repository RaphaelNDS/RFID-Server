package org.example.integracao

import org.example.model.LeituraAndroidEntity
import org.example.repository.LeituraAndroidRepository
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import java.time.LocalDate

/** Tela de leituras acima do teto de contagem (aqui 100; em produção 10.000): não conta a tabela inteira. */
@TestPropertySource(properties = ["rfid.leituras.teto-contagem=100"])
class LeiturasTetoContagemIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var repo: LeituraAndroidRepository

    @BeforeEach
    fun popular() {
        val hoje = LocalDate.now().atStartOfDay()
        repo.saveAll((0 until 120).map { LeituraAndroidEntity(tag = "E$it", tipo = "Notebook", dataHora = hoje.plusMinutes(it.toLong())) })
    }

    private fun abrir(vararg params: Pair<String, String>) = mvc.perform(
        get("/admin/leituras-android").with(Perfis.admin()).apply { params.forEach { (k, v) -> param(k, v) } }
    )

    @Test
    fun `acima do teto mostra mais de N e navega sem o total de paginas`() {
        abrir()
            .andExpect(model().attribute("totalExato", false))
            .andExpect(model().attribute("totalLeituras", 100L))
            .andExpect(model().attribute("totalPaginas", nullValue()))
            .andExpect(model().attribute("linkProxima", "/admin/leituras-android?pagina=2"))
            .andExpect(content().string(containsString("mais de 100 leituras")))
            .andExpect(content().string(not(containsString("Página 1 de"))))

        // Última página: o resto, sem "Próxima"
        abrir("pagina" to "3")
            .andExpect(model().attribute("leituras", hasSize<Any>(20)))
            .andExpect(model().attribute("linkProxima", nullValue()))
            .andExpect(model().attribute("linkAnterior", "/admin/leituras-android?pagina=2"))
    }

    @Test
    fun `com filtro abaixo do teto o total volta a ser exato`() {
        abrir("tag" to "e11")
            .andExpect(model().attribute("totalExato", true))
            .andExpect(model().attribute("totalLeituras", 11L))
            .andExpect(model().attribute("totalPaginas", 1))
    }
}
