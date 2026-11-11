package org.example.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.example.config.ColetorContatoInterceptor
import org.example.model.ColetorEntity
import org.example.repository.ColetorRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
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
    fun `diagnostico vai junto na gravacao de uma por minuto e nunca grava sozinho`() {
        val hora = OffsetDateTime.ofInstant(agora, zona)
        service.registrarContato("coletor-01", "10.0.0.5", "1.7.3", 116, "Ana", DiagnosticoColetor(filaInformada = true, filaPendentes = 3, relogioColetor = hora))
        // A fila muda, mas continua com pendentes: não grava (só zerar ou o relógio mudar de estado grava na hora)
        repeat(10) { service.registrarContato("coletor-01", "10.0.0.5", "1.7.3", 116, "Ana", DiagnosticoColetor(filaInformada = true, filaPendentes = it + 1)) }
        verify(exactly = 1) { repo.save(any()) }
        assertThat(banco.getValue("coletor-01").filaPendentes).isEqualTo(3)
        assertThat(banco.getValue("coletor-01").desvioRelogioSegundos).isZero()

        minutos(1)
        service.registrarContato("coletor-01", "10.0.0.5", "1.7.3", 116, "Ana",
            DiagnosticoColetor(filaInformada = true, filaPendentes = 0, relogioColetor = OffsetDateTime.ofInstant(agora.minusSeconds(150), zona)))
        assertThat(banco.getValue("coletor-01").filaPendentes).isZero()
        assertThat(banco.getValue("coletor-01").desvioRelogioSegundos).isEqualTo(-150)
        assertThat(service.listar().first { it.nome == "coletor-01" }.desvioLegivel).isEqualTo("atrasado 2 min")
    }

    @Test
    fun `fila que zera ou relogio que fica errado gravam na hora mesmo dentro do minuto`() {
        val hora = { segundos: Long -> OffsetDateTime.ofInstant(agora.plusSeconds(segundos), zona) }
        // Volta de horas offline: o primeiro lote grava a fila com pendentes
        service.registrarContato("coletor-01", "10.0.0.5", "2.0.2", 123, "Ana",
            DiagnosticoColetor(filaInformada = true, filaPendentes = 3000, relogioColetor = hora(0)))
        // Lotes do mesmo minuto com a fila diminuindo: não gravam
        service.registrarContato("coletor-01", "10.0.0.5", "2.0.2", 123, "Ana",
            DiagnosticoColetor(filaInformada = true, filaPendentes = 1500, relogioColetor = hora(0)))
        verify(exactly = 1) { repo.save(any()) }

        // A fila zera: grava na hora (antes ficava "fila parada" até o próximo contato)
        service.registrarContato("coletor-01", "10.0.0.5", "2.0.2", 123, "Ana",
            DiagnosticoColetor(filaInformada = true, filaPendentes = 0, relogioColetor = hora(0)))
        verify(exactly = 2) { repo.save(any()) }
        assertThat(banco.getValue("coletor-01").filaPendentes).isZero()

        // O relógio passa a ficar errado (4 min adiantado): grava na hora; continuar errado não grava de novo
        service.registrarContato("coletor-01", "10.0.0.5", "2.0.2", 123, "Ana",
            DiagnosticoColetor(filaInformada = true, filaPendentes = 0, relogioColetor = hora(240)))
        service.registrarContato("coletor-01", "10.0.0.5", "2.0.2", 123, "Ana",
            DiagnosticoColetor(filaInformada = true, filaPendentes = 0, relogioColetor = hora(250)))
        verify(exactly = 3) { repo.save(any()) }
        assertThat(service.listar().first { it.nome == "coletor-01" }.relogioErrado).isTrue()
    }

    @Test
    fun `desvio do relogio e espaco livre em texto para a tela`() {
        assertThat(ColetorService.descreverDesvio(2)).isEqualTo("certo")
        assertThat(ColetorService.descreverDesvio(-40)).isEqualTo("atrasado 40 s")
        assertThat(ColetorService.descreverDesvio(4 * 60 + 10)).isEqualTo("adiantado 4 min")
        assertThat(ColetorService.descreverDesvio(3 * 3600)).isEqualTo("adiantado 3 h")
        assertThat(ColetorService.descreverDesvio(-2 * 86_400)).isEqualTo("atrasado 2 dias")
        assertThat(ColetorService.descreverEspaco(350)).isEqualTo("350 MB")
        assertThat(ColetorService.descreverEspaco(1229)).isEqualTo("1,2 GB")
    }

    @Test
    fun `versao vinda do header e conferida`() {
        assertThat(ColetorContatoInterceptor.versaoApp(" 1.4.0 ")).isEqualTo("1.4.0")
        assertThat(ColetorContatoInterceptor.versaoApp("1.4.0\nFALSO")).isNull()
        assertThat(ColetorContatoInterceptor.versaoApp("x".repeat(51))).isNull()
        assertThat(ColetorContatoInterceptor.codigoVersao("13")).isEqualTo(13)
        assertThat(ColetorContatoInterceptor.codigoVersao("-1")).isNull()
        assertThat(ColetorContatoInterceptor.codigoVersao("abc")).isNull()
    }
}
