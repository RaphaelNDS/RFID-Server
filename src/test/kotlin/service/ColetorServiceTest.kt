package org.example.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.example.model.ColetorEntity
import org.example.repository.ColetorRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional

class ColetorServiceTest {

    private val zona = ZoneId.systemDefault()
    private var agora: Instant = LocalDateTime.of(2026, 10, 14, 10, 0).atZone(zona).toInstant()
    private val relogio = object : Clock() {
        override fun getZone() = zona
        override fun withZone(z: ZoneId?) = this
        override fun instant() = agora
    }

    private val banco = mutableMapOf<String, ColetorEntity>()
    private val repo = mockk<ColetorRepository>()

    private val service = ColetorService(repo, relogio, "coletor-01:chave-aaaaaaaaaaaaaaaa, coletor-02:chave-bbbbbbbbbbbbbbbb", 7)

    @BeforeEach
    fun configurar() {
        every { repo.findById(any()) } answers { Optional.ofNullable(banco[firstArg()]) }
        every { repo.save(any<ColetorEntity>()) } answers { firstArg<ColetorEntity>().also { banco[it.nome] = it } }
        every { repo.findAll() } answers { banco.values.toList() }
    }

    private fun minutos(m: Long) { agora = agora.plusSeconds(m * 60) }

    @Test
    fun `primeiro contato grava o coletor com versao IP e operador`() {
        service.registrarContato("coletor-01", "10.0.0.5", "1.4.0", 13, "Ana")

        val c = banco.getValue("coletor-01")
        assertThat(c.versaoApp).isEqualTo("1.4.0")
        assertThat(c.codigoVersao).isEqualTo(13)
        assertThat(c.ultimoIp).isEqualTo("10.0.0.5")
        assertThat(c.ultimoOperador).isEqualTo("Ana")
    }

    @Test
    fun `contatos repetidos sem mudanca gravam no maximo uma vez por minuto`() {
        service.registrarContato("coletor-01", "10.0.0.5", "1.4.0", 13, "Ana")
        repeat(50) { service.registrarContato("coletor-01", "10.0.0.5", "1.4.0", 13, "Ana") }
        verify(exactly = 1) { repo.save(any()) }

        minutos(1)
        service.registrarContato("coletor-01", "10.0.0.5", "1.4.0", 13, "Ana")
        verify(exactly = 2) { repo.save(any()) }
    }

    @Test
    fun `mudanca de versao ou IP grava na hora`() {
        service.registrarContato("coletor-01", "10.0.0.5", "1.3.0", null, "Ana")
        service.registrarContato("coletor-01", "10.0.0.5", "1.4.0", 13, "Ana")
        service.registrarContato("coletor-01", "10.0.0.9", "1.4.0", 13, "Ana")

        verify(exactly = 3) { repo.save(any()) }
        assertThat(banco.getValue("coletor-01").ultimoIp).isEqualTo("10.0.0.9")
    }

    @Test
    fun `login e ping sem operador nao apagam o ultimo operador`() {
        service.registrarContato("coletor-01", "10.0.0.5", "1.4.0", 13, "Ana")
        minutos(2)
        service.registrarContato("coletor-01", "10.0.0.5", "1.4.0", 13, null)

        assertThat(banco.getValue("coletor-01").ultimoOperador).isEqualTo("Ana")
    }

    @Test
    fun `erro no banco nao derruba a requisicao do coletor`() {
        every { repo.findById(any()) } throws RuntimeException("banco fora do ar")

        service.registrarContato("coletor-01", "10.0.0.5", "1.4.0", 13, "Ana")   // não lança
    }

    @Test
    fun `lista configurados que nunca conectaram situacao pelo tempo e versao desatualizada`() {
        service.registrarContato("coletor-01", "10.0.0.5", "1.3.9", 10, "Ana")
        service.registrarContato("coletor-antigo", "10.0.0.7", "1.4.0", 13, "Bruno")   // chave removida depois
        minutos(30)

        val lista = service.listar().associateBy { it.nome }

        assertThat(lista.keys).containsExactly("coletor-01", "coletor-02", "coletor-antigo")
        assertThat(lista.getValue("coletor-01").situacao).isEqualTo(SituacaoContato.PARADO)
        assertThat(lista.getValue("coletor-01").haQuantoTempo).isEqualTo("há 30 min")
        assertThat(lista.getValue("coletor-01").desatualizado).isTrue()   // 10 < 13
        assertThat(lista.getValue("coletor-02").situacao).isEqualTo(SituacaoContato.NUNCA)
        assertThat(lista.getValue("coletor-antigo").situacao).isEqualTo(SituacaoContato.CHAVE_REMOVIDA)
    }

    @Test
    fun `em uso nos ultimos 10 min e sem contato depois de 7 dias`() {
        service.registrarContato("coletor-01", "10.0.0.5", "1.4.0", 13, "Ana")
        service.registrarContato("coletor-02", "10.0.0.6", null, null, null)   // app anterior à 1.4.0
        minutos(5)
        assertThat(service.listar().first { it.nome == "coletor-01" }.situacao).isEqualTo(SituacaoContato.EM_USO)
        assertThat(service.listar().first { it.nome == "coletor-02" }.desatualizado).isTrue()

        minutos(7L * 24 * 60)
        val c = service.listar().first { it.nome == "coletor-01" }
        assertThat(c.situacao).isEqualTo(SituacaoContato.SEM_CONTATO)
        assertThat(c.haQuantoTempo).isEqualTo("há 7 dias")
    }

    @Test
    fun `versao vinda do header e conferida`() {
        assertThat(org.example.config.ColetorContatoInterceptor.versaoApp(" 1.4.0 ")).isEqualTo("1.4.0")
        assertThat(org.example.config.ColetorContatoInterceptor.versaoApp("1.4.0\nFALSO")).isNull()
        assertThat(org.example.config.ColetorContatoInterceptor.versaoApp("x".repeat(51))).isNull()
        assertThat(org.example.config.ColetorContatoInterceptor.codigoVersao("13")).isEqualTo(13)
        assertThat(org.example.config.ColetorContatoInterceptor.codigoVersao("-1")).isNull()
        assertThat(org.example.config.ColetorContatoInterceptor.codigoVersao("abc")).isNull()
    }
}
