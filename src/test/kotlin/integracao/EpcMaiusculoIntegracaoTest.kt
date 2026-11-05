package org.example.integracao

import org.assertj.core.api.Assertions.assertThat
import org.example.repository.TagRepository
import org.example.request.CadastroRequest
import org.example.request.MarcaRequest
import org.example.request.ModeloRequest
import org.example.request.TipoEquipamentoRequest
import org.example.service.CatalogoService
import org.example.service.RfidService
import org.example.suporte.IntegracaoBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** EPC sempre em maiúsculas: o PostgreSQL diferencia maiúsculas e "e2801170..." não batia com "E2801170...". */
class EpcMaiusculoIntegracaoTest : IntegracaoBase() {

    @Autowired lateinit var rfid: RfidService
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var catalogo: CatalogoService
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun hash(texto: String) =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cadastroCompleto(tag: String): CadastroRequest {
        val tipo = catalogo.salvarTipo(TipoEquipamentoRequest(nome = "Notebook")).id
        val marca = catalogo.salvarMarca(MarcaRequest(nome = "Dell", tipoId = tipo)).id
        val modelo = catalogo.salvarModelo(ModeloRequest(nome = "Latitude", marcaId = marca)).id
        return CadastroRequest(tag, "TI-1", "PAT-1", "SN-1", tipo, marca, modelo)
    }

    @Test
    fun `etiqueta cadastrada em minusculas e encontrada pela leitura em maiusculas do coletor`() {
        rfid.cadastrarTag(cadastroCompleto("  e28011700000020a1b2c3d4e "))

        assertThat(tags.findByTagReal("E28011700000020A1B2C3D4E")).isPresent
        assertThat(rfid.buscarPorTag("E28011700000020A1B2C3D4E")).isNotNull
        assertThat(rfid.buscarPorTag("e28011700000020a1b2c3d4e")).isNotNull
    }

    @Test
    fun `pendente em minusculas nao duplica a mesma etiqueta em maiusculas`() {
        rfid.salvarNaoCadastrada("e2004700000000000000abcd")
        rfid.salvarNaoCadastrada("E2004700000000000000ABCD")

        assertThat(jdbc.queryForObject("select count(*) from tags_nao_cadastradas where upper(tag) = 'E2004700000000000000ABCD'",
            Int::class.java)).isEqualTo(1)
    }

    /** A V18 roda de novo aqui sobre dados gravados do jeito antigo (o banco de teste nasce vazio). */
    @Test
    fun `V18 padroniza os dados antigos e nao falha com a mesma etiqueta gravada nas duas formas`() {
        fun inserirTag(tag: String) = jdbc.update(
            "insert into tags (codigo_interno, tag_hash, tag_real) values (?, ?, ?)", "TI-$tag", hash(tag), tag)
        inserirTag("e200aaaa")                 // só em minúsculas: vira maiúsculas, com o hash novo
        inserirTag("e200bbbb"); inserirTag("E200BBBB") // nas duas formas: ficam como estão
        jdbc.update("insert into tags_nao_cadastradas (tag, data_hora) values ('e200aaaa', now()), ('e200cccc', now())")

        jdbc.execute(ClassPathResource("db/migration/V18__epc_maiusculo.sql").getContentAsString(StandardCharsets.UTF_8))

        assertThat(jdbc.queryForObject("select tag_hash from tags where tag_real = 'E200AAAA'", String::class.java))
            .isEqualTo(hash("E200AAAA"))
        assertThat(jdbc.queryForList("select tag_real from tags where upper(tag_real) = 'E200BBBB' order by tag_real", String::class.java))
            .containsExactly("E200BBBB", "e200bbbb")
        // Pendente que já está cadastrada sai; a outra é padronizada
        assertThat(jdbc.queryForList("select tag from tags_nao_cadastradas order by tag", String::class.java))
            .containsExactly("E200CCCC")
    }
}
