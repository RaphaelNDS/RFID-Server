package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.repository.VersaoAppRepository
import org.example.service.AtualizacaoAppService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.util.LeitorApk
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import java.nio.file.Files

/**
 * Atualização do app pelo servidor, com APKs de verdade (src/test/resources/apk, assinados com chaves de teste).
 * A chave "A" é a configurada em application-test.yml; a "B" faz o papel de uma chave estranha.
 */
class AtualizacaoAppIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var service: AtualizacaoAppService
    @Autowired lateinit var versoes: VersaoAppRepository
    @Autowired lateinit var coletores: org.example.service.ColetorService

    @BeforeEach
    fun limparPasta() {
        if (Files.isDirectory(service.pasta)) Files.list(service.pasta).use { arquivos -> arquivos.forEach(Files::delete) }
    }

    private fun apk(nome: String) = javaClass.getResourceAsStream("/apk/$nome")!!.readAllBytes()

    private fun publicar(nome: String, notas: String = "Teste", perfil: org.springframework.test.web.servlet.request.RequestPostProcessor = Perfis.admin()): ResultActions =
        mvc.perform(
            multipart("/admin/app").file(MockMultipartFile("apk", nome, AtualizacaoAppService.TIPO_APK, apk(nome)))
                .param("notas", notas).with(perfil).with(csrf())
        )

    private fun coletor(url: String, codigoInstalado: String? = null): ResultActions =
        mvc.perform(get(url).header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
            .apply { codigoInstalado?.let { header("X-Versao-Codigo", it) } })

    // ---------- Leitura do APK ----------

    @Test
    fun `leitor tira pacote versao e certificado de dentro do APK`() {
        val arquivo = Files.createTempFile("teste", ".apk")
        Files.write(arquivo, apk("rfid-teste-2.0-2.apk"))

        val lido = LeitorApk.ler(arquivo)

        assertThat(lido.pacote).isEqualTo("br.com.bkpbr.rfidandroid")
        assertThat(lido.versaoNome).isEqualTo("2.0")
        assertThat(lido.codigo).isEqualTo(2)
        assertThat(lido.assinaturaValida).isTrue()
        assertThat(lido.certificados).containsExactly(service.certificadoEsperado)
        Files.delete(arquivo)
    }

    // ---------- Publicação ----------

    @Test
    fun `admin publica o APK e os coletores veem a versao com o SHA-256`() {
        publicar("rfid-teste-1.0-1.apk", notas = "Primeira versão")
            .andExpect(redirectedUrl("/admin/app"))
            .andExpect(flash().attribute("msg", containsString("Versão 1.0 (código 1) publicada")))

        val sha = LeitorApk.sha256Hex(apk("rfid-teste-1.0-1.apk"))
        coletor("/api/android/atualizacao", codigoInstalado = "0")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.versaoNome").value("1.0"))
            .andExpect(jsonPath("$.codigo").value(1))
            .andExpect(jsonPath("$.sha256").value(sha))
            .andExpect(jsonPath("$.notas").value("Primeira versão"))

        val baixado = coletor("/api/android/atualizacao/apk")
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", AtualizacaoAppService.TIPO_APK))
            .andExpect(header().string("X-Sha256", sha))
            .andReturn().response.contentAsByteArray
        assertThat(LeitorApk.sha256Hex(baixado)).isEqualTo(sha)

        // Nome do arquivo gerado pelo servidor, não o enviado
        assertThat(versoes.findAll().single().arquivo).isEqualTo("rfid-android-1.0-1.apk")
    }

    @Test
    fun `sem versao publicada o coletor recebe 204`() {
        coletor("/api/android/atualizacao").andExpect(status().isNoContent)
        coletor("/api/android/atualizacao/apk").andExpect(status().isNotFound)
    }

    @Test
    fun `APK assinado com outra chave e recusado`() {
        publicar("rfid-outra-chave-5.apk")
            .andExpect(flash().attribute("erro", containsString("assinado com outra chave")))
        assertThat(versoes.count()).isZero()
        assertThat(Files.list(service.pasta).use { it.count() }).isZero()   // o temporário foi apagado
    }

    @Test
    fun `APK de outro pacote e recusado`() {
        publicar("rfid-outro-pacote-5.apk")
            .andExpect(flash().attribute("erro", containsString("pacote 'br.com.outro.app'")))
    }

    @Test
    fun `arquivo que nao e APK e recusado`() {
        mvc.perform(
            multipart("/admin/app").file(MockMultipartFile("apk", "virus.apk", "application/octet-stream", "não sou um apk".toByteArray()))
                .with(Perfis.admin()).with(csrf())
        ).andExpect(flash().attribute("erro", containsString("não é um APK válido")))
    }

    @Test
    fun `versao igual ou menor que a publicada e recusada`() {
        publicar("rfid-teste-2.0-2.apk")
        publicar("rfid-teste-1.0-1.apk").andExpect(flash().attribute("erro", containsString("não é mais nova que a já publicada 2.0")))
        publicar("rfid-teste-2.0-2.apk").andExpect(flash().attribute("erro", containsString("não é mais nova")))
    }

    @Test
    fun `so as tres ultimas versoes guardam o arquivo`() {
        listOf("1.0-1", "2.0-2", "3.0-3", "4.0-4").forEach { publicar("rfid-teste-$it.apk") }

        val porCodigo = versoes.findAll().associateBy { it.codigo }
        assertThat(porCodigo.getValue(1).arquivoRemovido).isTrue()
        assertThat(porCodigo.filterKeys { it > 1 }.values).allMatch { !it.arquivoRemovido }
        assertThat(Files.exists(service.pasta.resolve("rfid-android-1.0-1.apk"))).isFalse()
        coletor("/api/android/atualizacao").andExpect(jsonPath("$.codigo").value(4))
    }

    @Test
    fun `coletor abaixo da versao publicada aparece como desatualizado na tela Coletores`() {
        publicar("rfid-teste-2.0-2.apk")
        coletores.esquecerContatosGravados()
        mvc.perform(get("/api/android/ping").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
            .header("X-Versao-App", "1.0").header("X-Versao-Codigo", "1")).andExpect(status().isOk)

        // Único coletor com versão conhecida, mas abaixo da publicada
        assertThat(coletores.listar().first { it.nome == "coletor-teste" }.desatualizado).isTrue()
        mvc.perform(get("/admin/coletores").with(Perfis.admin()))
            .andExpect(content().string(containsString("Versão publicada: 2.0 (código 2)")))
    }

    // ---------- Permissões ----------

    @Test
    fun `so ADMIN publica e ve a tela`() {
        publicar("rfid-teste-1.0-1.apk", perfil = Perfis.gestor()).andExpect(status().isForbidden)
        mvc.perform(get("/admin/app").with(Perfis.gestor())).andExpect(status().isForbidden)
        mvc.perform(get("/admin/app").with(Perfis.admin())).andExpect(status().isOk)
            .andExpect(content().string(containsString("Publicar versão nova")))
        assertThat(versoes.count()).isZero()
    }

    @Test
    fun `publicar exige CSRF`() {
        mvc.perform(
            multipart("/admin/app").file(MockMultipartFile("apk", "x.apk", AtualizacaoAppService.TIPO_APK, apk("rfid-teste-1.0-1.apk")))
                .with(Perfis.admin())
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `atualizacao do coletor exige a chave mas nao o operador`() {
        mvc.perform(get("/api/android/atualizacao")).andExpect(status().isUnauthorized)
        coletor("/api/android/atualizacao").andExpect(status().isNoContent)   // sem X-Sessao-Operador
    }
}
