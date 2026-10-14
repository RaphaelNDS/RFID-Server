package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.model.SessaoOperadorEntity
import org.example.repository.LeituraAndroidRepository
import org.example.repository.SessaoOperadorRepository
import org.example.repository.TagNaoCadastradaRepository
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.LeituraLoteService
import org.example.service.RfidService
import org.example.service.SessaoOperadorService
import org.example.suporte.IntegracaoBase
import org.example.suporte.OperadorTeste
import org.example.suporte.comoOperador
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** POST /api/android/leituras/lote: várias leituras numa requisição, com o resultado de cada uma. */
class LeituraLoteIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var operadores: OperadorTeste
    @Autowired lateinit var sessoes: SessaoOperadorRepository
    @Autowired lateinit var leituras: LeituraAndroidRepository
    @Autowired lateinit var naoCadastradas: TagNaoCadastradaRepository
    @Autowired lateinit var rfidService: RfidService
    @Autowired lateinit var catalogo: CatalogoService

    private lateinit var token: String

    @BeforeEach
    fun preparar() {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook")).id
        val marca = catalogo.salvarMarca(MarcaRequest(nome = "Dell", tipoId = tipo)).id
        val modelo = catalogo.salvarModelo(ModeloRequest(nome = "Latitude", marcaId = marca)).id
        rfidService.cadastrarTag(CadastroRequest("E-LOTE-1", "TI-1", "PAT-1", "SN-1", tipo, marca, modelo))
        rfidService.cadastrarTag(CadastroRequest("E-LOTE-2", "TI-2", "PAT-2", "SN-2", tipo, marca, modelo))

        // Sessão aberta há 1h: as leituras dos testes (de minutos atrás) foram feitas com o operador já logado
        val usuario = operadores.criarUsuario(email = "lote@x.com", nome = "Lucas Lote")
        token = sessaoAbertaHa(usuario.id!!, horas = 1)
    }

    private fun sessaoAbertaHa(usuarioId: Long, horas: Long): String {
        val novo = "token-manual-${UUID.randomUUID()}"
        val agora = LocalDateTime.now()
        sessoes.save(SessaoOperadorEntity(SessaoOperadorService.hash(novo), usuarioId, "coletor-teste", agora.minusHours(horas), agora.plusHours(11)))
        return novo
    }

    // Hora com milissegundos, como o coletor manda
    private fun minutosAtras(minutos: Long) = LocalDateTime.now().minusMinutes(minutos).truncatedTo(ChronoUnit.MILLIS)

    private fun iso(momento: LocalDateTime) = momento.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()

    private fun item(tag: String, lidoEm: LocalDateTime) = """{"tag":"$tag","lidoEm":"${iso(lidoEm)}"}"""

    private fun enviar(vararg itens: String, sessao: String = token): ResultActions =
        mvc.perform(
            post("/api/android/leituras/lote").comoOperador(sessao)
                .contentType(MediaType.APPLICATION_JSON).content("""{"itens":[${itens.joinToString(",")}]}""")
        )

    @Test
    fun `lote classifica cada tag e registra leitura e nao cadastrada de uma vez`() {
        val lida = minutosAtras(10)
        val naoCadastrada = "E-NOVA-${UUID.randomUUID()}"

        enviar(item("E-LOTE-1", lida), item(naoCadastrada, lida), item("E-LOTE-2", minutosAtras(5)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultados.length()").value(3))
            .andExpect(jsonPath("$.resultados[0].indice").value(0))
            .andExpect(jsonPath("$.resultados[0].status").value("CADASTRADA"))
            .andExpect(jsonPath("$.resultados[0].dados.patrimonio").value("PAT-1"))
            .andExpect(jsonPath("$.resultados[0].dados.modelo").value("Latitude"))
            .andExpect(jsonPath("$.resultados[1].status").value("NAO_CADASTRADA"))
            .andExpect(jsonPath("$.resultados[1].tag").value(naoCadastrada))
            .andExpect(jsonPath("$.resultados[2].status").value("CADASTRADA"))

        val gravada = leituras.findAll().single { it.tag == "E-LOTE-1" }
        assertThat(gravada.dataHora).isEqualTo(lida)
        assertThat(gravada.operador).isEqualTo("Lucas Lote")
        assertThat(leituras.findAll().count { it.tag == "E-LOTE-2" }).isEqualTo(1)
        assertThat(naoCadastradas.existsByTag(naoCadastrada)).isTrue()
    }

    @Test
    fun `reenvio do mesmo lote nao duplica as leituras`() {
        val lida = minutosAtras(3)

        repeat(2) {
            enviar(item("E-LOTE-1", lida)).andExpect(status().isOk)
                .andExpect(jsonPath("$.resultados[0].status").value("CADASTRADA"))
        }

        assertThat(leituras.findAll().count { it.tag == "E-LOTE-1" }).isEqualTo(1)
    }

    @Test
    fun `mesma tag lida em outro momento e outra leitura`() {
        enviar(item("E-LOTE-1", minutosAtras(8)), item("E-LOTE-1", minutosAtras(2))).andExpect(status().isOk)

        assertThat(leituras.findAll().count { it.tag == "E-LOTE-1" }).isEqualTo(2)
    }

    @Test
    fun `item invalido e recusado sem derrubar o resto do lote`() {
        enviar(item("  ", minutosAtras(1)), item("E-LOTE-1", minutosAtras(1)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultados[0].status").value("RECUSADA"))
            .andExpect(jsonPath("$.resultados[0].mensagem").value("Informe a TAG RFID"))
            .andExpect(jsonPath("$.resultados[1].status").value("CADASTRADA"))
    }

    @Test
    fun `leitura feita antes da sessao do operador e recusada e as outras passam`() {
        val usuario = operadores.criarUsuario(email = "sessao-curta-${UUID.randomUUID()}@x.com")
        val manual = sessaoAbertaHa(usuario.id!!, horas = 1)

        enviar(item("E-LOTE-1", minutosAtras(180)), item("E-LOTE-2", minutosAtras(10)), sessao = manual)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resultados[0].status").value("RECUSADA"))
            .andExpect(jsonPath("$.resultados[0].mensagem").value(containsString("faça login de novo")))
            .andExpect(jsonPath("$.resultados[1].status").value("CADASTRADA"))

        assertThat(leituras.findAll().none { it.tag == "E-LOTE-1" }).isTrue()
        assertThat(leituras.findAll().count { it.tag == "E-LOTE-2" }).isEqualTo(1)
    }

    @Test
    fun `lote acima do maximo e recusado com 400`() {
        val itens = Array(LeituraLoteService.TAMANHO_MAXIMO_LOTE + 1) { item("E-$it", minutosAtras(1)) }

        enviar(*itens)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.mensagem").value(containsString("o máximo é ${LeituraLoteService.TAMANHO_MAXIMO_LOTE}")))

        assertThat(naoCadastradas.existsByTag("E-0")).isFalse()
    }

    @Test
    fun `lote vazio responde sem resultados`() {
        enviar().andExpect(status().isOk).andExpect(jsonPath("$.resultados.length()").value(0))
    }

    @Test
    fun `lote exige operador logado`() {
        mvc.perform(
            post("/api/android/leituras/lote").header(OperadorTeste.HEADER_CHAVE, CHAVE_COLETOR)
                .contentType(MediaType.APPLICATION_JSON).content("""{"itens":[${item("E-LOTE-1", minutosAtras(1))}]}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.codigo").value("SESSAO_OPERADOR"))
    }
}
