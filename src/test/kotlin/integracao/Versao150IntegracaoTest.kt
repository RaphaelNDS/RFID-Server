package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.AreaAuditoria
import org.example.model.MotivoFalta
import org.example.repository.AuditoriaRepository
import org.example.repository.JustificativaInventarioRepository
import org.example.request.CadastroRequest
import org.example.service.ColetorService
import org.example.service.ConfiguracaoColetorService
import org.example.service.EsperadoInventarioService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.Perfis
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 1.5.0, pedidos do app 2.0.0: ficha do bem no coletor, esperado sem os bens achados depois em outro local,
 * justificativa dos faltantes e QR code de configuração do coletor.
 */
class Versao150IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var esperados: EsperadoInventarioService
    @Autowired lateinit var justificativas: JustificativaInventarioRepository
    @Autowired lateinit var auditoria: AuditoriaRepository
    @Autowired lateinit var coletores: ColetorService

    private val sufixo = UUID.randomUUID().toString().take(8).uppercase()
    private val token by lazy { operadores.sessaoPadrao(mvc, "v150-$sufixo@x.com") }

    @BeforeEach
    fun limpar() = coletores.esquecerContatosGravados()

    private fun coletor(url: String, json: String) =
        mvc.perform(post(url).comoOperador(token).contentType(MediaType.APPLICATION_JSON).content(json))

    private fun cadastrada(tag: String, patrimonio: String = "PAT-$tag") =
        rfid.cadastrarTag(CadastroRequest(tag = tag, codigoInterno = "TI-$tag", patrimonio = patrimonio))

    /**
     * Abre, lê e finaliza um inventário no [local] com a hora de finalização informada. Devolve o id. Sem iniciadoEm:
     * vale a hora de agora, depois de todas as finalizações do teste (a comparação usa o finalizado antes do início).
     */
    private fun inventario(local: String, finalizadoEm: String?, vararg tags: String): String {
        val id = UUID.randomUUID().toString()
        coletor("/api/android/inventarios", """{"id":"$id","local":"$local"}""").andExpect(status().isOk)
        coletor("/api/android/inventarios/$id/itens", """{"itens":[${tags.joinToString { """{"tag":"$it"}""" }}]}""").andExpect(status().isOk)
        if (finalizadoEm != null) coletor("/api/android/inventarios/$id/finalizar", """{"finalizadoEm":"$finalizadoEm"}""").andExpect(status().isOk)
        return id
    }

    // ---------- 1) Ficha do bem ----------

    @Test
    fun `ficha do bem traz o ultimo local e a ultima leitura`() {
        val tag = "E-FICHA-$sufixo"
        cadastrada(tag)
        inventario("Almox $sufixo", "2026-10-02T10:00:00-03:00", tag)
        inventario("Sala $sufixo", "2026-10-03T10:00:00-03:00", tag)
        coletor("/api/android/leituras/lote", """{"itens":[{"tag":"$tag"}]}""").andExpect(status().isOk)

        mvc.perform(get("/api/android/tags/$tag").comoOperador(token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.patrimonio").value("PAT-$tag"))
            .andExpect(jsonPath("$.ultimoLocal").value("Sala $sufixo"))   // o inventário finalizado mais recente
            .andExpect(jsonPath("$.ultimoInventarioEm").value(startsWith("2026-10-03T")))
            .andExpect(jsonPath("$.ultimaLeituraEm").isNotEmpty)
            .andExpect(jsonPath("$.ultimaLeituraPor").value("Olga Operadora"))
    }

    @Test
    fun `ficha de bem sem inventario nem leitura vem com os campos novos nulos e a nao cadastrada continua 404`() {
        val tag = "E-SEMFICHA-$sufixo"
        cadastrada(tag)
        inventario("Aberto $sufixo", null, tag)   // inventário em andamento não conta

        mvc.perform(get("/api/android/tags/$tag").comoOperador(token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ultimoLocal").value(nullValue()))
            .andExpect(jsonPath("$.ultimaLeituraEm").value(nullValue()))
        mvc.perform(get("/api/android/tags/E-NAOEXISTE-$sufixo").comoOperador(token)).andExpect(status().isNotFound)
    }

    // ---------- 2a) Bem achado em outro local ----------

    @Test
    fun `bem achado depois em outro local sai do esperado e aparece a parte na comparacao`() {
        val (a, b, c) = listOf("E-MOV-A-$sufixo", "E-MOV-B-$sufixo", "E-MOV-C-$sufixo")
        listOf(a, b, c).forEach { cadastrada(it) }
        val salaA = "Sala A $sufixo"
        // C foi achado na sala B ANTES do inventário de referência da sala A: continua esperado na A
        inventario("Sala B $sufixo", "2026-10-01T09:00:00-03:00", c)
        inventario(salaA, "2026-10-02T10:00:00-03:00", a, b, c)
        // Depois, B é achado na sala B: sai do esperado da sala A
        inventario("Sala B $sufixo", "2026-10-03T10:00:00-03:00", b)

        mvc.perform(get("/api/android/inventarios/esperado").param("local", salaA).comoOperador(token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.itens.length()").value(2))
            .andExpect(jsonPath("$.itens[0].tag").value(a))
            .andExpect(jsonPath("$.itens[1].tag").value(c))

        // Inventário novo da sala A que só leu A: falta C; B aparece como "encontrado em outro local"
        val novo = inventario(salaA, "2026-10-04T10:00:00-03:00", a)
        val comparacao = esperados.faltandoNoInventario(novo)
        assertThat(comparacao.faltando.map { it.tag }).containsExactly(c)
        assertThat(comparacao.emOutroLocal.map { it.item.tag to it.local }).containsExactly(b to "Sala B $sufixo")

        mvc.perform(get("/admin/inventarios/$novo").with(Perfis.admin()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Encontrados em outro local")))
            .andExpect(content().string(containsString("Sala B $sufixo")))
    }

    // ---------- 2b) Justificativa dos faltantes ----------

    @Test
    fun `justificativas sao gravadas depois de finalizado atualizadas no reenvio e aparecem na tela`() {
        val (a, b) = listOf("E-JUS-A-$sufixo", "E-JUS-B-$sufixo")
        listOf(a, b).forEach { cadastrada(it) }
        val local = "Depósito $sufixo"
        inventario(local, "2026-10-02T10:00:00-03:00", a, b)
        val id = inventario(local, "2026-10-03T10:00:00-03:00")   // nada lido: A e B faltando

        coletor("/api/android/inventarios/$id/justificativas",
            """[{"tag":"$a","motivo":"Emprestado","observacao":"Com o Pedro"},{"tag":"$b","motivo":"MANUTENCAO"},{"tag":"$b","motivo":"perdido"}]""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.recebidas").value(3))
            .andExpect(jsonPath("$.gravadas").value(2))
            .andExpect(jsonPath("$.invalidas[0]").value(b))

        // Reenvio igual não grava de novo; mudança de motivo atualiza a mesma
        coletor("/api/android/inventarios/$id/justificativas", """[{"tag":"$a","motivo":"EMPRESTADO","observacao":"Com o Pedro"}]""")
            .andExpect(jsonPath("$.gravadas").value(0))
        coletor("/api/android/inventarios/$id/justificativas", """[{"tag":"${b.lowercase()}","motivo":"em manutencao","observacao":"Na assistência"}]""")
            .andExpect(jsonPath("$.gravadas").value(1))

        val gravadas = justificativas.findByInventarioId(id).associateBy { it.tag }
        assertThat(gravadas).hasSize(2)
        assertThat(gravadas.getValue(a).motivo).isEqualTo(MotivoFalta.EMPRESTADO)
        assertThat(gravadas.getValue(b).observacao).isEqualTo("Na assistência")
        assertThat(gravadas.getValue(b).operador).isEqualTo("Olga Operadora")

        mvc.perform(get("/admin/inventarios/$id").with(Perfis.admin()))
            .andExpect(content().string(containsString(">Emprestado</span>")))
            .andExpect(content().string(containsString("Com o Pedro")))
            .andExpect(content().string(containsString(">Em manutenção</span>")))
        mvc.perform(get("/admin/inventarios/$id/exportar").param("formato", "csv").with(Perfis.admin()))
            .andExpect(content().string(containsString("Faltando: Emprestado (Com o Pedro)")))
    }

    @Test
    fun `justificativa de inventario de outro coletor e recusada`() {
        val id = inventario("Outro $sufixo", "2026-10-02T10:00:00-03:00")
        val outro = operadores.entrar(mvc, "v150-$sufixo@x.com", chave = "chave-de-leitura-0123456789")
        mvc.perform(post("/api/android/inventarios/$id/justificativas").comoOperador(outro, "chave-de-leitura-0123456789")
            .contentType(MediaType.APPLICATION_JSON).content("""[{"tag":"E1","motivo":"OUTRO"}]"""))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `motivo aceita o codigo e o nome sem acento nem maiusculas`() {
        assertThat(MotivoFalta.de("NAO_ENCONTRADO")).isEqualTo(MotivoFalta.NAO_ENCONTRADO)
        assertThat(MotivoFalta.de("Não encontrado")).isEqualTo(MotivoFalta.NAO_ENCONTRADO)
        assertThat(MotivoFalta.de(" em manutenção ")).isEqualTo(MotivoFalta.MANUTENCAO)
        assertThat(MotivoFalta.de("")).isNull()
        assertThat(MotivoFalta.de("sumiu")).isNull()
    }

    // ---------- 3) QR code de configuração ----------

    @Test
    fun `QR de configuracao so por POST de ADMIN fica na auditoria e leva servidor chave e certificado`() {
        mvc.perform(post("/admin/coletores/coletor-teste/qr").with(Perfis.admin()).with(csrf()).with { it.serverName = "172.16.0.50"; it })
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("<svg")))
            .andExpect(content().string(containsString("172.16.0.50:8443")))
            .andExpect(content().string(not(containsString("chave-de-teste-0123456789"))))   // a chave só vai dentro do QR

        assertThat(auditoria.findAll().filter { it.area == AreaAuditoria.COLETORES && it.alvo == "coletor-teste" }).hasSize(1)

        mvc.perform(post("/admin/coletores/coletor-teste/qr").with(Perfis.gestor()).with(csrf())).andExpect(status().isForbidden)
        mvc.perform(post("/admin/coletores/coletor-teste/qr").with(Perfis.admin())).andExpect(status().isForbidden)   // sem CSRF
        mvc.perform(post("/admin/coletores/nao-existe/qr").with(Perfis.admin()).with(csrf())).andExpect(status().isNotFound)

        mvc.perform(get("/admin/coletores").with(Perfis.admin()))
            .andExpect(content().string(containsString("/admin/coletores/coletor-teste/qr")))
    }

    @Test
    fun `texto do QR tem o formato combinado com o app`() {
        val porTela = ConfiguracaoColetorService("coletor-01:chave-aaaaaaaaaaaaaaaa", 8443, "").qr("coletor-01", "172.16.242.22")!!
        assertThat(porTela.texto).isEqualTo("RFIDCFG1|172.16.242.22:8443|chave-aaaaaaaaaaaaaaaa|")
        assertThat(porTela.toString()).doesNotContain("chave-aaaa")

        // Endereço configurado vence o da tela; aberto por localhost, não usa o localhost no QR
        val configurado = ConfiguracaoColetorService("coletor-01:chave-aaaaaaaaaaaaaaaa", 9443, "rfid.empresa.local").qr("coletor-01", "localhost")!!
        assertThat(configurado.endereco).isEqualTo("rfid.empresa.local:9443")
        val porLocalhost = ConfiguracaoColetorService("coletor-01:chave-aaaaaaaaaaaaaaaa", 8443, "").qr("coletor-01", "localhost")!!
        assertThat(porLocalhost.endereco).doesNotStartWith("127.")

        assertThat(ConfiguracaoColetorService("coletor-01:chave-aaaaaaaaaaaaaaaa", 8443, "").qr("outro", "x")).isNull()
    }

    @Test
    fun `o SVG do QR decodifica no texto combinado`() {
        val qr = ConfiguracaoColetorService("coletor-01:chave-aaaaaaaaaaaaaaaa", 8443, "").qr("coletor-01", "172.16.242.22")!!

        // Remonta a imagem pelos quadrados do SVG ("M{x} {y}h1v1h-1z"), ampliada 4x, e lê com o leitor do ZXing
        val tamanho = Regex("""viewBox="0 0 (\d+) (\d+)"""").find(qr.svg)!!.groupValues[1].toInt()
        val escala = 4
        val pixels = IntArray(tamanho * escala * tamanho * escala) { 0xFFFFFF }
        Regex("""M(\d+) (\d+)h1v1h-1z""").findAll(qr.svg).forEach { m ->
            val (x, y) = m.destructured.toList().map { it.toInt() }
            for (dy in 0 until escala) for (dx in 0 until escala) pixels[(y * escala + dy) * tamanho * escala + (x * escala + dx)] = 0
        }
        val fonte = com.google.zxing.RGBLuminanceSource(tamanho * escala, tamanho * escala, pixels)
        val lido = com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(fonte)))

        assertThat(lido.text).isEqualTo("RFIDCFG1|172.16.242.22:8443|chave-aaaaaaaaaaaaaaaa|")
    }
}
