package org.example.service

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * O que a tela do QR de configuração mostra. [svg] é gerado aqui, só com retângulos (não leva texto de fora).
 * [texto] é o conteúdo do QR, com a chave do coletor: não vai para a tela nem para o log (o toString o esconde).
 */
class QrConfiguracao(val coletor: String, val endereco: String, val impressaoDigital: String?, val svg: String, val texto: String) {
    override fun toString() = "QrConfiguracao(coletor=$coletor, endereco=$endereco)"
}

/**
 * QR code de configuração do coletor (tela Coletores, 1.5.0). O app lê pelo leitor de código de barras e preenche o
 * servidor e a chave; confia no certificado se a impressão digital do QR bater com a apresentada pelo servidor.
 *
 * Texto: `RFIDCFG1|<host:porta>|<chave do coletor>|<SHA-256 do certificado, com ":">` (sem HTTPS, o último campo
 * vai vazio). A chave está no QR: só ADMIN gera, depois de clicar, e cada vez fica na auditoria (ColetorWebController).
 *
 * Endereço: `rfid.coletores.endereco-qr` (RFID_ENDERECO_QR) se configurado; senão o endereço pelo qual o ADMIN abriu a
 * tela, quando não for desta própria máquina (localhost); senão o IPv4 da rede local desta máquina (fora as placas
 * virtuais do Docker, WSL e afins). A tela mostra o endereço escolhido, para conferir antes de ler.
 */
@Service
class ConfiguracaoColetorService(
    @Value("\${rfid.dispositivos.chaves:}") chavesConfiguradas: String,
    @Value("\${rfid.https.porta:8443}") private val porta: Int,
    @Value("\${rfid.coletores.endereco-qr:}") private val enderecoConfigurado: String,
    private val certificado: CertificadoHttps? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** nome → chave, no mesmo formato do ChaveDispositivoFilter (nome:chave, separados por vírgula). */
    private val chaves: Map<String, String> = chavesConfiguradas.split(',')
        .mapNotNull { item -> item.split(':', limit = 2).map { it.trim() }.takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].isNotEmpty() } }
        .associate { it[0] to it[1] }

    fun temChave(coletor: String) = coletor in chaves

    /** null = coletor sem chave configurada. [hostDaTela]: o host pelo qual o ADMIN abriu a página. */
    fun qr(coletor: String, hostDaTela: String?): QrConfiguracao? {
        val chave = chaves[coletor] ?: return null
        val endereco = "${host(hostDaTela)}:$porta"
        val digital = certificado?.impressaoDigital
        val texto = listOf(PREFIXO, endereco, chave, digital.orEmpty()).joinToString("|")
        return QrConfiguracao(coletor, endereco, digital, svg(texto), texto)
    }

    private fun host(hostDaTela: String?): String {
        enderecoConfigurado.trim().takeIf { it.isNotEmpty() }?.let { return it.substringBefore(':') }
        val daTela = hostDaTela?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 253 && HOST_VALIDO.matches(it) }
        if (daTela != null && !ehDestaMaquina(daTela)) return daTela
        return ipDaRedeLocal() ?: daTela ?: "localhost"
    }

    private fun ehDestaMaquina(host: String) = host == "localhost" || host.startsWith("127.") || host == "::1" || host == "[::1]"

    /** IPv4 de rede local (10.x, 172.16–31.x, 192.168.x) de uma placa ativa e não virtual. */
    private fun ipDaRedeLocal(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { placa -> VIRTUAIS.any { (placa.displayName ?: placa.name).contains(it, ignoreCase = true) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.onFailure { log.warn("Não foi possível listar os IPs desta máquina para o QR de configuração: {}", it.message) }.getOrNull()

    /** QR em SVG: um quadrado por módulo, numa só <path>. Correção M: resiste a reflexo da tela na leitura. */
    private fun svg(texto: String): String {
        val matriz = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val caminho = StringBuilder()
        for (y in 0 until matriz.height) for (x in 0 until matriz.width) if (matriz[x, y]) caminho.append("M$x ${y}h1v1h-1z")
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matriz.width} ${matriz.height}" shape-rendering="crispEdges" """ +
            """role="img" aria-label="QR code de configuração do coletor"><rect width="100%" height="100%" fill="#fff"/>""" +
            """<path fill="#000" d="$caminho"/></svg>"""
    }

    companion object {
        const val PREFIXO = "RFIDCFG1"
        private val HOST_VALIDO = Regex("^[a-z0-9.\\-]+$")
        private val VIRTUAIS = listOf("vEthernet", "WSL", "Hyper-V", "VirtualBox", "VMware", "docker", "Loopback", "Bluetooth")
    }
}
