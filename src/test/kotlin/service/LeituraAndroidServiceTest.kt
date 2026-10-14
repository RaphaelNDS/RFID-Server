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
        every { leituraRepo.save(any<LeituraAndroidEntity>()) } answers { firstArg() }
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

        verify { leituraRepo.save(capture(gravada)) }
        assertThat(gravada.captured.tipo).isEqualTo("Notebook")
        assertThat(gravada.captured.marca).isEqualTo("Dell")
        assertThat(gravada.captured.modelo).isEqualTo("Latitude")
        assertThat(gravada.captured.patrimonio).isEqualTo("P1")
        verify { eventos.publicar(gravada.captured) }
    }

    @Test
    fun `tag nao cadastrada e ignorada`() {
        every { tagRepo.findByTagReal("X") } returns Optional.empty()

        service.salvarLeitura("X")

        verify(exactly = 0) { leituraRepo.save(any<LeituraAndroidEntity>()) }
        verify(exactly = 0) { eventos.publicar(any()) }
    }

    @Test
    fun `tag sem modelo e ignorada`() {
        every { tagRepo.findByTagReal("E1") } returns Optional.of(tag(modeloId = null))

        service.salvarLeitura("E1")

        verify(exactly = 0) { leituraRepo.save(any<LeituraAndroidEntity>()) }
    }

    @Test
    fun `tag sem patrimonio e ignorada`() {
        every { tagRepo.findByTagReal("E1") } returns Optional.of(tag(patrimonio = ""))

        service.salvarLeitura("E1")

        verify(exactly = 0) { leituraRepo.save(any<LeituraAndroidEntity>()) }
    }
}
