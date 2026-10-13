package org.example.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.MarcaEntity
import org.example.model.ModeloEntity
import org.example.model.TipoEquipamentoEntity
import org.example.repository.MarcaRepository
import org.example.repository.ModeloRepository
import org.example.repository.TagRepository
import org.example.repository.TipoEquipamentoRepository
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Optional

class CatalogoServiceImplTest {

    private val tipoRepo = mockk<TipoEquipamentoRepository>(relaxed = true)
    private val marcaRepo = mockk<MarcaRepository>(relaxed = true)
    private val modeloRepo = mockk<ModeloRepository>(relaxed = true)
    private val tagRepo = mockk<TagRepository>(relaxed = true)

    private val service = CatalogoServiceImpl(tipoRepo, marcaRepo, modeloRepo, tagRepo)

    private val notebook = TipoEquipamentoEntity(id = 1, nome = "Notebook")
    private val dell = MarcaEntity(id = 2, nome = "Dell", tipo = notebook)

    @BeforeEach
    fun configurar() {
        every { tipoRepo.save(any<TipoEquipamentoEntity>()) } answers { firstArg() }
        every { marcaRepo.save(any<MarcaEntity>()) } answers { firstArg() }
        every { modeloRepo.save(any<ModeloEntity>()) } answers { firstArg() }
        every { modeloRepo.findById(any()) } returns Optional.empty()
        every { tipoRepo.findByNomeIgnoreCase(any()) } returns Optional.empty()
        every { marcaRepo.findByNomeIgnoreCaseAndTipoId(any(), any()) } returns Optional.empty()
        every { modeloRepo.findByNomeIgnoreCaseAndMarcaId(any(), any()) } returns Optional.empty()
        every { tipoRepo.findById(1) } returns Optional.of(notebook)
        every { tipoRepo.findById(999) } returns Optional.empty()
        every { marcaRepo.findById(2) } returns Optional.of(dell)
        every { marcaRepo.findById(999) } returns Optional.empty()
    }

    @Nested
    inner class Tipos {

        @Test
        fun `salva o tipo sem espacos nas pontas`() {
            assertThat(service.salvarTipo(TipoEquipamentoRequest(nome = "  Monitor  ")).nome).isEqualTo("Monitor")
        }

        @Test
        fun `nome vazio e recusado`() {
            assertThatThrownBy { service.salvarTipo(TipoEquipamentoRequest(nome = "   ")) }
                .hasMessage("Nome do tipo não pode ser vazio")
        }

        @Test
        fun `tipo repetido (sem diferenciar maiusculas) e recusado`() {
            every { tipoRepo.findByNomeIgnoreCase("notebook") } returns Optional.of(notebook)

            assertThatThrownBy { service.salvarTipo(TipoEquipamentoRequest(nome = "notebook")) }
                .hasMessage("Tipo já cadastrado!")
        }

        @Test
        fun `editar para o nome de outro tipo e recusado`() {
            val monitor = TipoEquipamentoEntity(id = 3, nome = "Monitor")
            every { tipoRepo.findById(3) } returns Optional.of(monitor)
            every { tipoRepo.findByNomeIgnoreCase("Notebook") } returns Optional.of(notebook)

            assertThatThrownBy { service.editarTipo(3, TipoEquipamentoRequest(nome = "Notebook")) }
                .hasMessage("Já existe outro tipo com esse nome")
        }

        @Test
        fun `editar mantendo o proprio nome e permitido`() {
            every { tipoRepo.findByNomeIgnoreCase("NOTEBOOK") } returns Optional.of(notebook)

            assertThat(service.editarTipo(1, TipoEquipamentoRequest(nome = "NOTEBOOK")).nome).isEqualTo("NOTEBOOK")
        }

        @Test
        fun `tipo com marcas vinculadas nao pode ser excluido`() {
            every { marcaRepo.existsByTipoId(1) } returns true

            assertThatThrownBy { service.excluirTipo(1) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Não é possível excluir tipo com marcas vinculadas")

            verify(exactly = 0) { tipoRepo.deleteById(any()) }
        }

        @Test
        fun `tipo sem marcas e excluido`() {
            every { marcaRepo.existsByTipoId(1) } returns false

            service.excluirTipo(1)

            verify { tipoRepo.deleteById(1) }
        }
    }

    @Nested
    inner class Marcas {

        @Test
        fun `marca sem tipo e recusada com mensagem (antes era NullPointerException)`() {
            assertThatThrownBy { service.salvarMarca(MarcaRequest(nome = "HP", tipoId = null)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Selecione o tipo de equipamento da marca")
        }

        @Test
        fun `marca com tipo inexistente e recusada`() {
            assertThatThrownBy { service.salvarMarca(MarcaRequest(nome = "HP", tipoId = 999)) }
                .hasMessage("Tipo de equipamento não encontrado")
        }

        @Test
        fun `marca repetida no mesmo tipo e recusada`() {
            every { marcaRepo.findByNomeIgnoreCaseAndTipoId("dell", 1) } returns Optional.of(dell)

            assertThatThrownBy { service.salvarMarca(MarcaRequest(nome = "dell", tipoId = 1)) }
                .hasMessage("Marca já cadastrada para esse tipo!")
        }

        @Test
        fun `marca valida fica ligada ao tipo`() {
            val salva = service.salvarMarca(MarcaRequest(nome = "Lenovo", tipoId = 1))

            assertThat(salva.tipo).isSameAs(notebook)
        }

        @Test
        fun `marca com modelos nao pode ser excluida`() {
            every { modeloRepo.existsByMarcaId(2) } returns true

            assertThatThrownBy { service.excluirMarca(2) }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Nested
    inner class Modelos {

        @Test
        fun `modelo sem marca e recusado com mensagem (antes era NullPointerException)`() {
            assertThatThrownBy { service.salvarModelo(ModeloRequest(nome = "Latitude", marcaId = null)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Selecione a marca do modelo")
        }

        @Test
        fun `modelo com marca inexistente e recusado`() {
            assertThatThrownBy { service.salvarModelo(ModeloRequest(nome = "Latitude", marcaId = 999)) }
                .hasMessage("Marca não encontrada")
        }

        @Test
        fun `modelo valido fica ligado a marca`() {
            assertThat(service.salvarModelo(ModeloRequest(nome = "Latitude", marcaId = 2)).marca).isSameAs(dell)
        }

        @Test
        fun `nome vazio e recusado`() {
            assertThatThrownBy { service.salvarModelo(ModeloRequest(nome = "", marcaId = 2)) }
                .hasMessage("Nome do modelo não pode ser vazio")
        }
    }

    @Nested
    inner class ItensEmUsoPorEtiquetas {

        @Test
        fun `modelo usado por etiquetas nao pode ser excluido (antes nao havia verificacao)`() {
            every { tagRepo.countByModeloId(5) } returns 3

            assertThatThrownBy { service.excluirModelo(5) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("3 etiqueta(s) usam este modelo")

            verify(exactly = 0) { modeloRepo.deleteById(any()) }
        }

        @Test
        fun `modelo sem etiquetas e excluido`() {
            every { tagRepo.countByModeloId(5) } returns 0

            service.excluirModelo(5)

            verify { modeloRepo.deleteById(5) }
        }

        @Test
        fun `marca sem modelos mas usada por etiquetas nao pode ser excluida`() {
            every { modeloRepo.existsByMarcaId(2) } returns false
            every { tagRepo.countByMarcaId(2) } returns 1

            assertThatThrownBy { service.excluirMarca(2) }.hasMessageContaining("1 etiqueta(s) usam esta marca")

            verify(exactly = 0) { marcaRepo.deleteById(any()) }
        }

        @Test
        fun `tipo sem marcas mas usado por etiquetas nao pode ser excluido`() {
            every { marcaRepo.existsByTipoId(1) } returns false
            every { tagRepo.countByTipoEquipamentoId(1) } returns 2

            assertThatThrownBy { service.excluirTipo(1) }.hasMessageContaining("2 etiqueta(s) usam este tipo")

            verify(exactly = 0) { tipoRepo.deleteById(any()) }
        }
    }

    @Test
    fun `grafico conta modelos por tipo`() {
        val monitor = TipoEquipamentoEntity(id = 3, nome = "Monitor")
        every { tipoRepo.findAll() } returns listOf(notebook, monitor)
        every { modeloRepo.countByMarcaTipoId(1) } returns 4
        every { modeloRepo.countByMarcaTipoId(3) } returns 0

        assertThat(service.graficoModelosPorTipo()).isEqualTo(mapOf("Notebook" to 4L, "Monitor" to 0L))
    }
}
