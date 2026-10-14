package org.example.repository

import org.assertj.core.api.Assertions.assertThat
import org.example.model.LeituraAndroidEntity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalDateTime

/** Consultas do dashboard contra o schema real (migrações do Flyway). */
@DataJpaTest
@ActiveProfiles("test")
class LeituraAndroidRepositoryTest {

    @Autowired lateinit var repo: LeituraAndroidRepository

    private val dia = LocalDate.of(2026, 10, 3)
    private val de = dia.atStartOfDay()
    private val ate = dia.plusDays(1).atStartOfDay()

    private fun leitura(quando: LocalDateTime, tipo: String? = "Notebook", marca: String? = "Dell") =
        repo.save(LeituraAndroidEntity(tag = "E1", tipo = tipo, marca = marca, dataHora = quando))

    @BeforeEach
    fun popular() {
        leitura(dia.atTime(0, 0))                     // primeiro instante do dia: entra
        leitura(dia.atTime(23, 59, 59, 999_000_000))  // último instante do dia: entra (antes ficava de fora)
        leitura(dia.plusDays(1).atStartOfDay())       // meia-noite seguinte: fica de fora
        leitura(dia.minusDays(1).atTime(23, 59))      // dia anterior: fica de fora
        leitura(dia.atTime(12, 0), tipo = null, marca = "HP")
    }

    @Test
    fun `contarEntre usa intervalo meia-noite a meia-noite seguinte`() {
        assertThat(repo.contarEntre(de, ate)).isEqualTo(3)
    }

    @Test
    fun `contagem por dia agrupa pela data`() {
        val porDia = repo.contarPorDiaEntre(dia.minusDays(1).atStartOfDay(), ate.plusDays(1))
            .associate { it[0].toString() to (it[1] as Number).toLong() }

        assertThat(porDia).containsEntry("2026-10-02", 1L).containsEntry("2026-10-03", 3L).containsEntry("2026-10-04", 1L)
    }

    @Test
    fun `contagem por tipo inclui tipo vazio e vem da maior para a menor`() {
        val porTipo = repo.contarPorTipoEntre(de, ate).map { (it[0] as String?) to (it[1] as Number).toLong() }

        assertThat(porTipo).containsExactly("Notebook" to 2L, null to 1L)
    }

    @Test
    fun `contagem por marca respeita o periodo`() {
        val porMarca = repo.contarPorMarcaEntre(de, ate).associate { it[0] to (it[1] as Number).toLong() }

        assertThat(porMarca).isEqualTo(mapOf("Dell" to 2L, "HP" to 1L))
    }

    @Test
    fun `exclusao da retencao apaga so o que for anterior ao limite`() {
        // limite = início do dia: apaga a leitura de 23:59 do dia anterior, mantém as do dia em diante
        val apagadas = repo.apagarAnterioresA(dia.atStartOfDay())

        assertThat(apagadas).isEqualTo(1)
        assertThat(repo.count()).isEqualTo(4)
    }

    @Test
    fun `contarDesde conta a partir do instante informado`() {
        // 12:00 (inclusive), 23:59:59 e a meia-noite seguinte
        assertThat(repo.contarDesde(dia.atTime(12, 0))).isEqualTo(3)
    }
}
