package org.example.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.*
import org.example.repository.*
import org.example.request.CadastroRequest
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Optional

class RfidServiceTest {

    private val tagRepo = mockk<TagRepository>(relaxed = true)
    private val naoRepo = mockk<TagNaoCadastradaRepository>(relaxed = true)
    private val tipoRepo = mockk<TipoEquipamentoRepository>()
    private val marcaRepo = mockk<MarcaRepository>()
    private val modeloRepo = mockk<ModeloRepository>()

    private val service = RfidService(tagRepo, naoRepo, tipoRepo, marcaRepo, modeloRepo)

    private fun sha256(texto: String) =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray()).joinToString("") { "%02x".format(it) }

    // Catálogo coerente: Notebook > Dell > Latitude; e um Monitor > LG > UltraWide para os casos de mistura
    private val notebook = TipoEquipamentoEntity(id = 1, nome = "Notebook")
    private val dell = MarcaEntity(id = 2, nome = "Dell", tipo = notebook)
    private val latitude = ModeloEntity(id = 3, nome = "Latitude", marca = dell)
    private val monitor = TipoEquipamentoEntity(id = 10, nome = "Monitor")
    private val lg = MarcaEntity(id = 20, nome = "LG", tipo = monitor)
    private val ultrawide = ModeloEntity(id = 30, nome = "UltraWide", marca = lg)

    private fun catalogo() {
        listOf(notebook, monitor).forEach { every { tipoRepo.findById(it.id) } returns Optional.of(it) }
        listOf(dell, lg).forEach { every { marcaRepo.findById(it.id) } returns Optional.of(it) }
        listOf(latitude, ultrawide).forEach { every { modeloRepo.findById(it.id) } returns Optional.of(it) }
        every { tipoRepo.findById(999) } returns Optional.empty()
        every { marcaRepo.findById(999) } returns Optional.empty()
        every { modeloRepo.findById(999) } returns Optional.empty()
        every { tagRepo.existsByTagReal(any()) } returns false
    }

    private fun cadastrar(tipo: Long?, marca: Long?, modelo: Long?) =
        service.cadastrarTag(CadastroRequest(tag = "E1", codigoInterno = "TI-1",
            tipoEquipamentoId = tipo, marcaId = marca, modeloId = modelo))

    // ---------------- cadastro ----------------

    @Test
    fun `cadastra a etiqueta com hash e remove das pendentes`() {
        catalogo()
        val salvo = slot<TagEntity>()
        every { tagRepo.save(capture(salvo)) } answers { firstArg() }

        service.cadastrarTag(CadastroRequest(tag = "E1", codigoInterno = "TI-1", patrimonio = "P1", numeroSerie = "S1",
            tipoEquipamentoId = 1, marcaId = 2, modeloId = 3))

        assertThat(salvo.captured.tagReal).isEqualTo("E1")
        assertThat(salvo.captured.tagHash).isEqualTo(sha256("E1"))
        assertThat(salvo.captured.modeloId).isEqualTo(3)
        verify { naoRepo.deleteByTag("E1") }
    }

    @Test
    fun `etiqueta duplicada e recusada sem gravar`() {
        every { tagRepo.existsByTagReal("E1") } returns true

        assertThatThrownBy { service.cadastrarTag(CadastroRequest(tag = "E1")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Esta etiqueta já está cadastrada")

        verify(exactly = 0) { tagRepo.save(any<TagEntity>()) }
        verify(exactly = 0) { naoRepo.deleteByTag(any()) }
    }

    @Test
    fun `etiqueta sem catalogo nenhum e aceita`() {
        catalogo()
        every { tagRepo.save(any<TagEntity>()) } answers { firstArg() }

        cadastrar(null, null, null)

        verify { tagRepo.save(any<TagEntity>()) }
    }

    @Test
    fun `marca de outro tipo e recusada`() {
        catalogo()

        assertThatThrownBy { cadastrar(tipo = 1, marca = 20, modelo = null) }
            .hasMessage("A marca LG não pertence ao tipo de equipamento Notebook")
        verify(exactly = 0) { tagRepo.save(any<TagEntity>()) }
    }

    @Test
    fun `modelo de outra marca e recusado`() {
        catalogo()

        assertThatThrownBy { cadastrar(tipo = 1, marca = 2, modelo = 30) }
            .hasMessage("O modelo UltraWide não pertence à marca Dell")
    }

    @Test
    fun `marca sem tipo e recusada`() {
        catalogo()

        assertThatThrownBy { cadastrar(tipo = null, marca = 2, modelo = null) }
            .hasMessage("Selecione o tipo de equipamento da marca Dell")
    }

    @Test
    fun `modelo sem marca e recusado`() {
        catalogo()

        assertThatThrownBy { cadastrar(tipo = 1, marca = null, modelo = 3) }
            .hasMessage("Selecione a marca do modelo Latitude")
    }

    @Test
    fun `item de catalogo inexistente e recusado`() {
        catalogo()

        assertThatThrownBy { cadastrar(999, null, null) }.hasMessage("Tipo de equipamento não encontrado")
        assertThatThrownBy { cadastrar(1, 999, null) }.hasMessage("Marca não encontrada")
        assertThatThrownBy { cadastrar(1, 2, 999) }.hasMessage("Modelo não encontrado")
    }

    @Test
    fun `listagem busca o catalogo uma vez so (antes eram 3 consultas por etiqueta)`() {
        every { tipoRepo.findAll() } returns listOf(notebook)
        every { marcaRepo.findAll() } returns listOf(dell)
        every { modeloRepo.findAll() } returns listOf(latitude)
        every { tagRepo.findAll() } returns (1..50).map {
            TagEntity(tagHash = "h$it", tagReal = "E$it", tipoEquipamentoId = 1, marcaId = 2, modeloId = 3)
        }

        val lista = service.listarParaAdmin()

        assertThat(lista).hasSize(50).allMatch { it.tipoNome == "Notebook" && it.marcaNome == "Dell" && it.modeloNome == "Latitude" }
        verify(exactly = 0) { tipoRepo.findById(any()) }
        verify(exactly = 0) { marcaRepo.findById(any()) }
        verify(exactly = 0) { modeloRepo.findById(any()) }
    }

    @Test
    fun `etiqueta em branco e recusada`() {
        assertThatThrownBy { service.cadastrarTag(CadastroRequest(tag = " ")) }
            .hasMessage("Informe a TAG RFID")
    }

    // ---------------- consulta ----------------

    @Test
    fun `busca por tag inexistente devolve null`() {
        every { tagRepo.findByTagHash(sha256("X")) } returns null

        assertThat(service.buscarPorTag("X")).isNull()
    }

    @Test
    fun `busca por tag traz os nomes do catalogo`() {
        val tipo = TipoEquipamentoEntity(id = 1, nome = "Notebook")
        val marca = MarcaEntity(id = 2, nome = "Dell", tipo = tipo)
        every { tagRepo.findByTagHash(sha256("E1")) } returns
            TagEntity(tagHash = sha256("E1"), tagReal = "E1", codigoInterno = "TI-1", tipoEquipamentoId = 1,
                marcaId = 2, modeloId = 3, patrimonio = "P1", numeroSerie = "S1")
        every { tipoRepo.findById(1) } returns Optional.of(tipo)
        every { marcaRepo.findById(2) } returns Optional.of(marca)
        every { modeloRepo.findById(3) } returns Optional.empty() // modelo apagado: vira null, sem erro

        val resposta = service.buscarPorTag("E1")!!

        assertThat(resposta.tipo).isEqualTo("Notebook")
        assertThat(resposta.marca).isEqualTo("Dell")
        assertThat(resposta.modelo).isNull()
        assertThat(resposta.patrimonio).isEqualTo("P1")
    }

    // ---------------- não cadastradas ----------------

    @Test
    fun `tag desconhecida vira pendente`() {
        every { tagRepo.existsByTagReal("E9") } returns false
        every { naoRepo.existsByTag("E9") } returns false
        every { naoRepo.save(any<TagNaoCadastradaEntity>()) } answers { firstArg() }

        service.salvarNaoCadastrada("E9")

        verify { naoRepo.save(match<TagNaoCadastradaEntity> { it.tag == "E9" }) }
    }

    @Test
    fun `tag ja cadastrada nao vira pendente`() {
        every { tagRepo.existsByTagReal("E1") } returns true

        service.salvarNaoCadastrada("E1")

        verify(exactly = 0) { naoRepo.save(any<TagNaoCadastradaEntity>()) }
    }

    @Test
    fun `tag ja pendente nao e duplicada`() {
        every { tagRepo.existsByTagReal("E9") } returns false
        every { naoRepo.existsByTag("E9") } returns true

        service.salvarNaoCadastrada("E9")

        verify(exactly = 0) { naoRepo.save(any<TagNaoCadastradaEntity>()) }
    }

    @Test
    fun `tag pendente em branco e recusada`() {
        assertThatThrownBy { service.salvarNaoCadastrada("") }.hasMessage("Informe a TAG RFID")
    }

    @Test
    fun `pendentes saem da mais recente para a mais antiga`() {
        val agora = LocalDateTime.now()
        every { naoRepo.findAll() } returns listOf(
            TagNaoCadastradaEntity(tag = "ANTIGA", dataHora = agora.minusDays(2)),
            TagNaoCadastradaEntity(tag = "NOVA", dataHora = agora),
            TagNaoCadastradaEntity(tag = "MEIO", dataHora = agora.minusDays(1))
        )

        assertThat(service.listarNaoCadastradas().map { it.tag }).containsExactly("NOVA", "MEIO", "ANTIGA")
    }
}
