package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.LeituraAndroidEntity
import org.example.repository.LeituraAndroidRepository
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.LocalDate
import java.time.LocalDateTime

class LeiturasPaginacaoIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var repo: LeituraAndroidRepository

    private val hoje = LocalDate.now()

    @BeforeEach
    fun popular() {
        // 120 leituras de hoje, uma por minuto (a mais recente é "E119")
        repo.saveAll((0 until 120).map {
            LeituraAndroidEntity(tag = "E$it", tipo = "Notebook", marca = "Dell", modelo = "Latitude",
                patrimonio = "P$it", numeroSerie = "S$it", operador = if (it % 2 == 0) "Olga Operadora" else "Bruno",
                dataHora = hoje.atStartOfDay().plusMinutes(it.toLong()))
        })
    }

    @Suppress("UNCHECKED_CAST")
    private fun leiturasDa(resultado: MvcResult) =
        resultado.modelAndView!!.model["leituras"] as List<LeituraAndroidEntity>

    private fun abrir(vararg params: Pair<String, String>) = mvc.perform(
        get("/admin/leituras-android").with(Perfis.admin()).apply { params.forEach { (k, v) -> param(k, v) } }
    )

    @Test
    fun `primeira pagina traz 50 leituras, da mais recente para a mais antiga`() {
        val resultado = abrir()
            .andExpect(status().isOk)
            .andExpect(model().attribute("totalLeituras", 120L))
            .andExpect(model().attribute("paginaAtual", 1))
            .andExpect(model().attribute("totalPaginas", 3))
            .andExpect(model().attribute("linkAnterior", nullValue()))
            .andExpect(model().attribute("linkProxima", "/admin/leituras-android?pagina=2"))
            .andReturn()

        val leituras = leiturasDa(resultado)
        assertThat(leituras).hasSize(50)
        assertThat(leituras.first().tag).isEqualTo("E119")
        assertThat(leituras.last().tag).isEqualTo("E70")
    }

    @Test
    fun `ultima pagina traz o resto`() {
        val resultado = abrir("pagina" to "3")
            .andExpect(model().attribute("linkProxima", nullValue()))
            .andExpect(model().attribute("linkAnterior", "/admin/leituras-android?pagina=2"))
            .andReturn()

        assertThat(leiturasDa(resultado)).hasSize(20)
    }

    @Test
    fun `pagina alem da ultima mostra a ultima (link antigo depois de filtrar)`() {
        abrir("pagina" to "99").andExpect(model().attribute("paginaAtual", 3))
        abrir("pagina" to "-5").andExpect(model().attribute("paginaAtual", 1))
    }

    @Test
    fun `links das paginas mantem os filtros`() {
        abrir("operador" to "olga", "tipo" to "note")
            .andExpect(model().attribute("totalLeituras", 60L))
            .andExpect(model().attribute("linkProxima", "/admin/leituras-android?tipo=note&operador=olga&pagina=2"))
            .andExpect(content().string(containsString("tipo=note&amp;operador=olga&amp;pagina=2")))
    }

    @Test
    fun `filtros ignoram maiusculas e tratam porcentagem como texto`() {
        abrir("marca" to "DELL").andExpect(model().attribute("totalLeituras", 120L))
        abrir("tag" to "%").andExpect(model().attribute("totalLeituras", 0L)) // antes "%" trazia tudo
        abrir("tag" to "e11").andExpect(model().attribute("totalLeituras", 11L)) // E11 e E110..E119
    }

    @Test
    fun `filtro por operador`() {
        abrir("operador" to "bruno").andExpect(model().attribute("totalLeituras", 60L))
    }

    @Test
    fun `filtro de dia inclui o ultimo instante do dia`() {
        val ontem = hoje.minusDays(1)
        repo.save(LeituraAndroidEntity(tag = "FIM-DO-DIA", dataHora = ontem.atTime(23, 59, 59, 900_000_000)))
        repo.save(LeituraAndroidEntity(tag = "MEIA-NOITE", dataHora = hoje.atStartOfDay().minusNanos(0)))

        val resultado = abrir("dia" to ontem.toString())
            .andExpect(model().attribute("totalLeituras", 1L))
            .andReturn()

        assertThat(leiturasDa(resultado).single().tag).isEqualTo("FIM-DO-DIA")
    }

    @Test
    fun `atalhos de dia mostram so os ultimos 31 dias, do mais recente para o mais antigo`() {
        repo.save(LeituraAndroidEntity(tag = "ANTIGA", dataHora = LocalDateTime.now().minusDays(60)))
        repo.save(LeituraAndroidEntity(tag = "SEMANA", dataHora = LocalDateTime.now().minusDays(7)))

        val resultado = abrir().andReturn()

        @Suppress("UNCHECKED_CAST")
        val dias = resultado.modelAndView!!.model["contadorPorDia"] as Map<String, Long>
        assertThat(dias.keys).containsExactly(hoje.toString(), hoje.minusDays(7).toString())
    }

    @Test
    fun `navegacao aparece na tela`() {
        abrir("pagina" to "2")
            .andExpect(content().string(containsString("Página 2 de 3")))
            .andExpect(content().string(containsString("rel=\"prev\"")))
            .andExpect(content().string(containsString("rel=\"next\"")))
            .andExpect(content().string(containsString("120 leituras")))
    }
}
