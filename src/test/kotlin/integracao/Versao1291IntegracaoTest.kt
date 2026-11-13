package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.TagNaoCadastradaEntity
import org.example.repository.TagNaoCadastradaRepository
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.UUID

/** 1.29.1: botão Limpar pendentes. */
class Versao1291IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var pendentes: TagNaoCadastradaRepository
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var rfid: RfidService

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    @Test
    fun `limpar apaga todas as pendentes, nao mexe nas cadastradas e pede permissao de excluir`() {
        rfid.cadastrarTag(CadastroRequest(tag = "CAD-$s", codigoInterno = "TI-CAD-$s"))
        (1..3).forEach { pendentes.save(TagNaoCadastradaEntity(tag = "PEND$it-$s", dataHora = LocalDateTime.now())) }
        val total = pendentes.count()

        // O botão aparece para quem exclui, com o total (mesmo numa busca), e não para quem só vê
        mvc.perform(get("/admin/tags-pendentes").param("busca", "PEND1-$s").with(Perfis.admin()))
            .andExpect(content().string(containsString("Limpar pendentes")))
            .andExpect(content().string(containsString("Apagar todas as $total etiqueta(s)")))
        mvc.perform(get("/admin/tags-pendentes").with(Perfis.usuario()))
            .andExpect(content().string(not(containsString("Limpar pendentes"))))
        mvc.perform(post("/admin/tags-pendentes/limpar").with(Perfis.gestor()).with(csrf())).andExpect(status().isForbidden)
        assertThat(pendentes.count()).isEqualTo(total)

        mvc.perform(post("/admin/tags-pendentes/limpar").with(Perfis.admin()).with(csrf()))
            .andExpect(flash().attribute("msg", "$total etiqueta(s) pendente(s) apagada(s)."))
        assertThat(pendentes.count()).isZero()
        assertThat(tags.existsByTagReal("CAD-$s")).isTrue()
        mvc.perform(post("/admin/tags-pendentes/limpar").with(Perfis.admin()).with(csrf()))
            .andExpect(flash().attribute("msg", "Não havia etiquetas pendentes."))
    }
}
