package org.example.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.example.model.*
import org.example.repository.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Optional

class LeituraAndroidServiceTest {

    private val leituraRepo = mockk<LeituraAndroidRepository>(relaxed = true)
    private val tagRepo = mockk<TagRepository>()
    private val tipoRepo = mockk<TipoEquipamentoRepository>()
    private val marcaRepo = mockk<MarcaRepository>()
    private val modeloRepo = mockk<ModeloRepository>()
    private val eventos = mockk<LeituraEventosService>(relaxed = true)

    private val service = LeituraAndroidService(leituraRepo, tagRepo, tipoRepo, marcaRepo, modeloRepo, eventos)

    private val tipo = TipoEquipamentoEntity(id = 1, nome = "Notebook")
    private val marca = MarcaEntity(id = 2, nome = "Dell", tipo = tipo)
    private val modelo = ModeloEntity(id = 3, nome = "Latitude", marca = marca)

    @BeforeEach
    fun configurar() {
        every { leituraRepo.saveAndFlush(any<LeituraAndroidEntity>()) } answers { firstArg() }
        every { tipoRepo.findById(1) } returns Optional.of(tipo)
        every { marcaRepo.findById(2) } returns Optional.of(marca)
        every { modeloRepo.findById(3) } returns Optional.of(modelo)
    }

    private fun tag(patrimonio: String = "P1", modeloId: Long? = 3) = TagEntity(
        tagHash = "h", tagReal = "E1", codigoInterno = "TI-1", tipoEquipamentoId = 1,
        marcaId = 2, modeloId = modeloId, patrimonio = patrimonio, numeroSerie = "S1"
    )

    @Test
    fun `leitura de tag completa e gravada com os nomes e publicada em tempo real`() {
        every { tagRepo.findByTagReal("E1") } returns Optional.of(tag())
        val gravada = slot<LeituraAndroidEntity>()

        service.salvarLeitura("E1")

        verify { leituraRepo.saveAndFlush(capture(gravada)) }
        assertThat(gravada.captured.tipo).isEqualTo("Notebook")
        assertThat(gravada.captured.marca).isEqualTo("Dell")
        assertThat(gravada.captured.modelo).isEqualTo("Latitude")
        assertThat(gravada.captured.patrimonio).isEqualTo("P1")
        verify { eventos.publicar(gravada.captured) }
    }

    @Test
    fun `leitura recusada pelo indice unico conta como repetida e nao vai para a tela`() {
        every { tagRepo.findByTagReal("E1") } returns Optional.of(tag())
        // Outra requisição gravou a mesma leitura entre a conferência e a gravação (envio simultâneo)
        every { leituraRepo.existsByTagAndLidoEmColetor(any(), any()) } returns false
        every { leituraRepo.saveAndFlush(any<LeituraAndroidEntity>()) } throws
            DataIntegrityViolationException("could not execute statement",
                java.sql.SQLException("duplicate key value violates unique constraint \"uk_leituras_android_tag_lido_em_coletor\"", "23505"))

        val resultado = service.salvarLeitura("E1", OffsetDateTime.now().minusMinutes(5))

        assertThat(resultado).isEqualTo(ResultadoLeitura.REPETIDA)
        verify(exactly = 0) { eventos.publicar(any()) }
    }

    @Test
    fun `outro erro de integridade nao vira repetida e sobe`() {
        every { tagRepo.findByTagReal("E1") } returns Optional.of(tag())
        every { leituraRepo.existsByTagAndLidoEmColetor(any(), any()) } returns false
        // NOT NULL (23502) ou texto maior que a coluna (22001): não é reenvio, a leitura não pode sumir calada
        every { leituraRepo.saveAndFlush(any<LeituraAndroidEntity>()) } throws
            DataIntegrityViolationException("could not execute statement",
                RuntimeException("envoltório", java.sql.SQLException("null value in column \"tag\" violates not-null constraint", "23502")))

        org.assertj.core.api.Assertions.assertThatThrownBy { service.salvarLeitura("E1", OffsetDateTime.now().minusMinutes(5)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        verify(exactly = 0) { eventos.publicar(any()) }
    }

    @Test
    fun `violacao de unicidade e achada no meio da cadeia de causas`() {
        val profunda = DataIntegrityViolationException("x", RuntimeException("y", java.sql.SQLException("dup", "23505")))
        assertThat(org.example.util.violacaoDeUnicidade(profunda)).isTrue()
        assertThat(org.example.util.violacaoDeUnicidade(DataIntegrityViolationException("sem causa"))).isFalse()
        assertThat(org.example.util.violacaoDeUnicidade(DataIntegrityViolationException("fk", java.sql.SQLException("fk", "23503")))).isFalse()
    }

    @Test
    fun `tag nao cadastrada e ignorada`() {
        every { tagRepo.findByTagReal("X") } returns Optional.empty()

        service.salvarLeitura("X")

        verify(exactly = 0) { leituraRepo.saveAndFlush(any<LeituraAndroidEntity>()) }
        verify(exactly = 0) { eventos.publicar(any()) }
    }

    @Test
    fun `tag sem modelo grava a leitura com o que houver (antes era descartada calada)`() {
        every { tagRepo.findByTagReal("E1") } returns Optional.of(tag(modeloId = null))
        val gravada = slot<LeituraAndroidEntity>()

        val resultado = service.salvarLeitura("E1")

        assertThat(resultado).isEqualTo(ResultadoLeitura.REGISTRADA)
        verify { leituraRepo.saveAndFlush(capture(gravada)) }
        assertThat(gravada.captured.modelo).isNull()
        assertThat(gravada.captured.marca).isEqualTo("Dell")
    }

    @Test
    fun `tag sem patrimonio tambem grava a leitura`() {
        every { tagRepo.findByTagReal("E1") } returns Optional.of(tag(patrimonio = ""))

        assertThat(service.salvarLeitura("E1")).isEqualTo(ResultadoLeitura.REGISTRADA)
        verify { leituraRepo.saveAndFlush(any<LeituraAndroidEntity>()) }
    }

    @Test
    fun `relogio do coletor adiantado grava a hora do recebimento e guarda a do coletor para reconhecer o reenvio`() {
        every { tagRepo.findByTagReal("E1") } returns Optional.of(tag())
        val futuro = OffsetDateTime.now().plusHours(2)
        val gravada = slot<LeituraAndroidEntity>()
        every { leituraRepo.existsByTagAndLidoEmColetor("E1", any()) } returns false

        service.salvarLeitura("E1", futuro)

        verify { leituraRepo.saveAndFlush(capture(gravada)) }
        val horaDoColetor = futuro.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        assertThat(gravada.captured.lidoEmColetor).isEqualTo(horaDoColetor)
        assertThat(gravada.captured.dataHora).isBefore(horaDoColetor)

        // Reenvio da mesma leitura: a conferência usa a hora do coletor, que não muda
        every { leituraRepo.existsByTagAndLidoEmColetor("E1", horaDoColetor) } returns true
        assertThat(service.salvarLeitura("E1", futuro)).isEqualTo(ResultadoLeitura.REPETIDA)
    }
}