package org.example.integracao

import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/** Nos testes o banco é em memória, então o backup fica inativo: aqui se testa o acesso e as mensagens. */
class BackupIntegracaoTest : IntegracaoBase() {

    @Test
    fun `status mostra o ultimo backup e o botao so para o admin`() {
        mvc.perform(get("/status").with(Perfis.admin()))
            .andExpect(content().string(containsString("Último backup")))
            .andExpect(content().string(containsString("inativo (banco em memória)")))
            .andExpect(content().string(containsString("Fazer backup agora")))

        mvc.perform(get("/status").with(Perfis.gestor()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Fazer backup agora"))))
    }

    @Test
    fun `backup manual com banco em memoria avisa em vez de dar erro 500`() {
        mvc.perform(post("/admin/backup").with(Perfis.admin()).with(csrf()))
            .andExpect(redirectedUrl("/status"))
            .andExpect(flash().attribute("erro", "Backup indisponível: banco em memória"))
    }

    @Test
    fun `so o admin pode pedir backup`() {
        mvc.perform(post("/admin/backup").with(Perfis.gestor()).with(csrf()))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `backup manual exige token CSRF`() {
        mvc.perform(post("/admin/backup").with(Perfis.admin()))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `api de status inclui o ultimo backup`() {
        mvc.perform(get("/api/status").with(Perfis.admin()))
            .andExpect(jsonPath("$.ultimoBackup").value(containsString("inativo")))
    }
}
