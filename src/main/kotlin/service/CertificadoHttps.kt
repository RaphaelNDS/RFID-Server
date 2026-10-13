package org.example.service

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date

/**
 * Certificado do HTTPS do servidor (arquivo PKCS12).
 *
 * - Se o arquivo NÃO existir, gera um certificado próprio (autoassinado), válido por 10 anos, com os IPs
 *   desta máquina e os nomes de rfid.https.nomes — serve para rede interna, sem domínio.
 * - Se o arquivo existir, só carrega. NUNCA gera outro sozinho: a impressão digital está fixada nos
 *   coletores, e um certificado novo desconectaria todos. Se não abrir (senha errada, arquivo corrompido),
 *   o servidor para com erro claro.
 * - A impressão digital SHA-256 aparece no log e na tela Status: é ela que o responsável confere no coletor.
 *
 * Para usar um certificado da empresa, aponte rfid.https.keystore para o .p12 dela e informe rfid.https.senha.
 */
@Component
class CertificadoHttps(
    @Value("\${rfid.https.keystore:./data/rfid-https.p12}") caminho: String,
    @Value("\${rfid.https.senha:}") private val senhaConfigurada: String,
    /** Nomes e IPs extras do certificado (ex.: rfid-servidor,192.168.0.10), para o navegador aceitar pelo nome. */
    @Value("\${rfid.https.nomes:}") nomesExtras: String
) {

    companion object {
        const val ALIAS = "rfid"
        private const val ANOS_VALIDADE = 10L
        private const val DIAS_AVISO_VENCIMENTO = 60L
        private val FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault())
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** SHA-256 do certificado (DER) em hexadecimal com ":", o mesmo formato do navegador, do keytool e do app. */
        fun impressaoDigital(certificado: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificado.encoded).joinToString(":") { "%02X".format(it) }
    }

    private val log = LoggerFactory.getLogger(javaClass)

    val arquivo: File = File(caminho).absoluteFile
    private val arquivoSenha = File(arquivo.path + ".senha")
    private val extras: List<String> = nomesExtras.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    // Preenchidos por garantir() (backing fields: a classe é aberta pelo plugin do Spring)
    private var certificadoCarregado: X509Certificate? = null
    private var senhaCarregada: String = ""
    private var digitalCalculada: String? = null

    val certificado: X509Certificate? get() = certificadoCarregado
    val senha: String get() = senhaCarregada

    /** Calculada uma vez (a tela Status é montada em toda página). Nula com o HTTPS desligado. */
    val impressaoDigital: String? get() = digitalCalculada

    /** Nomes e IPs que constam no certificado (vale também para um certificado da empresa, já carregado). */
    val nomesDoCertificado: List<String>
        get() = certificadoCarregado?.subjectAlternativeNames?.mapNotNull { it.getOrNull(1)?.toString() }.orEmpty()

    /**
     * Nome do servidor para montar endereços de redirecionamento a partir do que o cliente pediu (header Host).
     * Só devolve o nome pedido se ele estiver no certificado; senão, um nome conhecido — um Host forjado
     * não pode fazer o servidor redirecionar ou anunciar outro endereço.
     */
    fun nomeConfiavel(pedido: String): String {
        val nomes = nomesDoCertificado
        nomes.firstOrNull { it.equals(pedido, ignoreCase = true) }?.let { return it }

        log.warn("Pedido com nome de servidor que não está no certificado ('{}'): usando um nome conhecido no endereço", pedido.take(100))
        return extras.firstOrNull()
            ?: nomes.firstOrNull { it != "localhost" && it != "127.0.0.1" }
            ?: "localhost"
    }

    /** Carrega o certificado, gerando um novo só se o arquivo não existir. Chamado antes de o Tomcat abrir o HTTPS. */
    @Synchronized
    fun garantir() {
        if (certificadoCarregado != null) return

        val existia = arquivo.exists()
        senhaCarregada = obterSenha(existia)
        val cert = if (existia) carregar() else gerar()

        certificadoCarregado = cert
        digitalCalculada = impressaoDigital(cert)

        log.info("Impressão digital SHA-256 do certificado HTTPS: {}", digitalCalculada)
        log.info("Confira essa impressão digital ao confirmar o servidor nas Configurações de cada coletor (também aparece na tela Status)")

        val restante = Duration.between(Instant.now(), cert.notAfter.toInstant())
        if (restante.toDays() < DIAS_AVISO_VENCIMENTO) {
            log.warn("O certificado HTTPS vence em {} dia(s) ({}). Para trocar: gere/instale o novo e confirme-o de novo em cada coletor",
                restante.toDays(), FORMATO.format(cert.notAfter.toInstant()))
        }
    }

    private fun obterSenha(arquivoExiste: Boolean): String {
        if (senhaConfigurada.isNotBlank()) return senhaConfigurada
        if (arquivoSenha.exists()) return arquivoSenha.readText().trim()

        if (arquivoExiste) {
            throw IllegalStateException(
                "O certificado HTTPS $arquivo existe, mas a senha não foi encontrada: informe rfid.https.senha " +
                    "(RFID_HTTPS_SENHA) ou restaure o arquivo ${arquivoSenha.name}. Um certificado novo NÃO é gerado " +
                    "sozinho, porque desconectaria todos os coletores."
            )
        }

        // Certificado gerado aqui: senha aleatória guardada ao lado, só para o servidor reabrir o arquivo
        val gerada = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
        arquivoSenha.parentFile?.mkdirs()
        arquivoSenha.writeText(gerada)
        arquivoSenha.setReadable(false, false)
        arquivoSenha.setReadable(true, true)
        return gerada
    }

    private fun carregar(): X509Certificate {
        val ks = KeyStore.getInstance("PKCS12")
        try {
            arquivo.inputStream().use { ks.load(it, senhaCarregada.toCharArray()) }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Não foi possível abrir o certificado HTTPS $arquivo (senha errada ou arquivo corrompido): ${e.message}. " +
                    "Restaure o arquivo do backup ou corrija a senha. Um certificado novo NÃO é gerado sozinho, " +
                    "porque desconectaria todos os coletores.", e
            )
        }

        val alias = if (ks.containsAlias(ALIAS)) ALIAS else ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw IllegalStateException("O arquivo $arquivo não tem chave privada para o HTTPS")
        val cert = ks.getCertificate(alias) as X509Certificate

        log.info("Certificado HTTPS carregado de {} ({}; válido até {})",
            arquivo, cert.subjectX500Principal.name, FORMATO.format(cert.notAfter.toInstant()))
        return cert
    }

    private fun gerar(): X509Certificate {
        val ips = (enderecosDestaMaquina() + extras.filter { IPV4.matches(it) }).distinct()
        val nomes = (listOf("localhost") + listOfNotNull(runCatching { InetAddress.getLocalHost().hostName }.getOrNull()) +
            extras.filterNot { IPV4.matches(it) }).distinct()

        val chaves = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val agora = Instant.now()
        val nome = X500Name("CN=Sistema RFID, O=RFID-Server")

        val alternativos = GeneralNames(
            (nomes.map { GeneralName(GeneralName.dNSName, it) } + ips.map { GeneralName(GeneralName.iPAddress, it) }).toTypedArray()
        )

        val construtor = JcaX509v3CertificateBuilder(
            nome,
            BigInteger(64, SecureRandom()),
            Date.from(agora.minus(Duration.ofDays(1))),            // folga para relógios atrasados
            Date.from(agora.atZone(ZoneId.systemDefault()).plusYears(ANOS_VALIDADE).toInstant()),
            nome,
            chaves.public
        )
            .addExtension(Extension.subjectAlternativeName, false, alternativos)
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))

        val cert = JcaX509CertificateConverter().getCertificate(construtor.build(JcaContentSignerBuilder("SHA256withRSA").build(chaves.private)))

        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry(ALIAS, chaves.private, senhaCarregada.toCharArray(), arrayOf(cert))
        arquivo.parentFile?.mkdirs()
        arquivo.outputStream().use { ks.store(it, senhaCarregada.toCharArray()) }

        log.info("Certificado HTTPS gerado em {} (válido até {}; nomes: {}; IPs: {})",
            arquivo, FORMATO.format(cert.notAfter.toInstant()), nomes.joinToString(), ips.joinToString())
        log.warn("Guarde uma cópia de {} e {} junto com os backups: se forem perdidos, todos os coletores precisarão confirmar um certificado novo",
            arquivo.name, arquivoSenha.name)
        return cert
    }

    /** IPv4 das interfaces ativas (inclusive 127.0.0.1), para o certificado valer pelo IP no navegador. */
    private fun enderecosDestaMaquina(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .distinct()
        }.getOrElse {
            log.warn("Não foi possível listar os IPs desta máquina para o certificado: usando só 127.0.0.1", it)
            listOf("127.0.0.1")
        }
}
