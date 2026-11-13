package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.model.InventarioEntity
import org.example.model.StatusInventario
import org.example.repository.InventarioRepository
import org.example.request.CadastroRequest
import org.example.service.InventarioCiclicoService
import org.example.service.PatrimonioCadastrosService
import org.example.service.RfidService
import org.example.service.SituacaoCiclo
import org.example.suporte.IntegracaoBase
import org.example.suporte.Perfis
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash
import java.time.LocalDateTime
import java.util.UUID

/** 1.27.0: inventário cíclico. */
class Versao1270IntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var cadastros: PatrimonioCadastrosService
    @Autowired lateinit var inventarios: InventarioRepository
    @Autowired lateinit var ciclico: InventarioCiclicoService

    private val s = UUID.randomUUID().toString().take(6).uppercase()

    private fun local(nome: String, bens: Int): Long {
        val l = cadastros.salvarLocal(null, "$nome $s", null)
        repeat(bens) { i -> rfid.cadastrarTag(CadastroRequest(tag = "IC$nome$i-$s", codigoInterno = "TI-IC$nome$i-$s", patrimonio = "P-IC$nome$i-$s", localId = l.id)) }
        return l.id
    }

    private fun inventariado(localId: Long, diasAtras: Long) {
        val quando = LocalDateTime.now().minusDays(diasAtras)
        inventarios.save(InventarioEntity(id = UUID.randomUUID().toString(), localId = localId, dispositivo = "teste",
            status = StatusInventario.FINALIZADO, iniciadoEm = quando.minusHours(1), finalizadoEm = quando))
    }

    @Test
    fun `nunca contado e atrasado entram na semana, em dia fica de fora, e a tela e o painel mostram`() {
        val nunca = local("Nunca", 4)
        val emDia = local("EmDia", 2)
        val atrasado = local("Atrasado", 2)
        inventariado(emDia, 10)
        inventariado(atrasado, 30)

        assertThatThrownBy { ciclico.configurar(true, 60) }.hasMessageContaining("1 a 52")
        ciclico.configurar(true, 4)

        val p = ciclico.plano()
        val porId = p.locais.associateBy { it.local.id }
        assertThat(porId.getValue(nunca).situacao).isEqualTo(SituacaoCiclo.NUNCA)
        assertThat(porId.getValue(atrasado).situacao).isEqualTo(SituacaoCiclo.ATRASADO)
        assertThat(porId.getValue(emDia).situacao).isEqualTo(SituacaoCiclo.EM_DIA)
        assertThat(porId.getValue(emDia).venceEm).isEqualTo(LocalDateTime.now().minusDays(10).toLocalDate().plusDays(28))
        assertThat(p.estaSemana.map { it.local.id }).contains(nunca, atrasado).doesNotContain(emDia)
        // O nunca contado vem antes do atrasado
        assertThat(p.locais.map { it.local.id }.indexOf(nunca)).isLessThan(p.locais.map { it.local.id }.indexOf(atrasado))

        // Inventariar o atrasado hoje tira ele da semana
        inventariado(atrasado, 0)
        assertThat(ciclico.plano().estaSemana.map { it.local.id }).doesNotContain(atrasado)

        mvc.perform(get("/admin/inventario-ciclico").with(Perfis.usuario()))
            .andExpect(content().string(containsString("Nunca $s")))
            .andExpect(content().string(containsString("Esta semana")))
        mvc.perform(get("/dashboard").with(Perfis.usuario()))
            .andExpect(content().string(containsString("Inventário cíclico:")))
        mvc.perform(post("/admin/inventario-ciclico").with(Perfis.admin()).with(csrf()).param("semanas", "2"))
            .andExpect(flash().attribute("msg", containsString("desligado")))
        assertThat(ciclico.locaisDaSemana()).isNull()
        mvc.perform(post("/admin/inventario-ciclico").with(Perfis.usuario()).with(csrf()).param("ativo", "true").param("semanas", "2"))
            .andExpect { assertThat(it.response.status).isIn(302, 403) }
        assertThat(ciclico.configuracao().ativo).isFalse()
    }
}
