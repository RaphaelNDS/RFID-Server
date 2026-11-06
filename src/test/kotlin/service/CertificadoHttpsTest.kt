package org.example.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.example.config.HttpsConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant

/** Certificado do HTTPS: gerado uma vez, estável, e nunca trocado sozinho. */
class CertificadoHttpsTest {

    @TempDir
    lateinit var pasta: File

    private fun certificado(senha: String = "", nomes: String = "") =
        CertificadoHttps(File(pasta, "rfid-https.p12").path, senha, nomes)

    @Test
    fun `gera o certificado na primeira vez com senha aleatoria guardada ao lado`() {
        val c = certificado(nomes = "rfid-servidor,192.168.50.10")

        c.garantir()

        assertThat(File(pasta, "rfid-https.p12")).exists()
        assertThat(File(pasta, "rfid-https.p12.senha").readText()).hasSizeGreaterThan(20)
        assertThat(c.impressaoDigital).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}")

        val validade = Duration.between(Instant.now(), c.certificado!!.notAfter.toInstant())
        assertThat(validade.toDays()).isGreaterThan(9 * 365)

        val alternativos = c.certificado!!.subjectAlternativeNames.map { it[1].toString() }
        assertThat(alternativos).contains("localhost", "127.0.0.1", "rfid-servidor", "192.168.50.10")
    }

    @Test
    fun `arquivo da senha fica legivel so pelo dono (600 no Linux, ACL no Windows)`() {
        certificado().garantir()
        val senha = File(pasta, "rfid-https.p12.senha").toPath()

        val posix = Files.getFileAttributeView(senha, PosixFileAttributeView::class.java)
        if (posix != null) {
            assertThat(PosixFilePermissions.toString(posix.readAttributes().permissions())).isEqualTo("rw-------")
        } else {
            val acl = Files.getFileAttributeView(senha, AclFileAttributeView::class.java)!!
            val nomes = acl.acl.map { it.principal().name.substringAfter('\\').lowercase() }
            // Só o dono, o SYSTEM e os Administradores (nos dois idiomas): nada de Usuários, Todos ou herdados da pasta
            val permitidos = setOf(acl.owner.name.substringAfter('\\').lowercase(), "system", "sistema", "administrators", "administradores")
            assertThat(nomes).isNotEmpty().allMatch { it in permitidos }
            assertThat(acl.acl).allMatch { it.type() == AclEntryType.ALLOW }
        }
        // E o servidor continua conseguindo ler a senha (reiniciar carrega o mesmo certificado)
        assertThat(certificado().also { it.garantir() }.impressaoDigital).isNotBlank()
    }

    @Test
    fun `senha de instalacao anterior tambem e protegida ao subir`() {
        certificado().garantir()
        val senha = File(pasta, "rfid-https.p12.senha")
        // Simula o arquivo gravado pela versão anterior, com permissões abertas
        Files.getFileAttributeView(senha.toPath(), PosixFileAttributeView::class.java)
            ?.setPermissions(PosixFilePermissions.fromString("rw-r--r--"))

        certificado().garantir()

        Files.getFileAttributeView(senha.toPath(), PosixFileAttributeView::class.java)?.let {
            assertThat(PosixFilePermissions.toString(it.readAttributes().permissions())).isEqualTo("rw-------")
        }
    }

    @Test
    fun `nome do servidor para redirecionar so vem do certificado e nunca do Host forjado`() {
        val c = certificado(nomes = "rfid-servidor").also { it.garantir() }

        assertThat(c.nomeConfiavel("RFID-SERVIDOR")).isEqualTo("rfid-servidor")
        assertThat(c.nomeConfiavel("127.0.0.1")).isEqualTo("127.0.0.1")
        assertThat(c.nomeConfiavel("site-malicioso.com")).isEqualTo("rfid-servidor")
        assertThat(c.nomeConfiavel("\"}{injetado")).isEqualTo("rfid-servidor")
    }

    @Test
    fun `reiniciar carrega o mesmo certificado e a impressao digital nao muda`() {
        val primeiro = certificado().also { it.garantir() }.impressaoDigital

        val depois = certificado().also { it.garantir() }.impressaoDigital

        assertThat(depois).isEqualTo(primeiro)
    }

    @Test
    fun `certificado corrompido para o servidor em vez de gerar outro`() {
        certificado().garantir()
        File(pasta, "rfid-https.p12").writeText("isto não é um PKCS12")

        assertThatThrownBy { certificado().garantir() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Não foi possível abrir o certificado HTTPS")
            .hasMessageContaining("NÃO é gerado sozinho")
        assertThat(File(pasta, "rfid-https.p12").readText()).isEqualTo("isto não é um PKCS12")
    }

    @Test
    fun `senha errada para o servidor com mensagem clara`() {
        certificado().garantir()

        assertThatThrownBy { certificado(senha = "senha-errada").garantir() }
            .hasMessageContaining("senha errada ou arquivo corrompido")
    }

    @Test
    fun `certificado existente sem a senha nao e substituido`() {
        certificado().garantir()
        File(pasta, "rfid-https.p12.senha").delete()

        assertThatThrownBy { certificado().garantir() }
            .hasMessageContaining("a senha não foi encontrada")
    }

    @Test
    fun `configuracao do Tomcat liga o HTTPS na 8443 e deixa a 8082 para redirecionar`() {
        val factory = TomcatServletWebServerFactory()

        HttpsConfig(habilitado = true, portaHttps = 8443, portaHttp = 8082, certificado = certificado()).configurar(factory)

        assertThat(factory.port).isEqualTo(8443)
        assertThat(factory.ssl.isEnabled).isTrue()
        assertThat(factory.session.cookie.secure).isTrue()
        val http = factory.additionalTomcatConnectors.single()
        assertThat(http.port).isEqualTo(8082)
        assertThat(http.redirectPort).isEqualTo(8443)
    }

    @Test
    fun `com HTTPS desligado nada muda e nenhum certificado e gerado`() {
        val factory = TomcatServletWebServerFactory().apply { port = 8082 }

        HttpsConfig(habilitado = false, portaHttps = 8443, portaHttp = 8082, certificado = certificado()).configurar(factory)

        assertThat(factory.port).isEqualTo(8082)
        assertThat(factory.ssl).isNull()
        assertThat(File(pasta, "rfid-https.p12")).doesNotExist()
    }
}
