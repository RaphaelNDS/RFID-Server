package org.example.util

import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.apksig.util.DataSources
import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest

/** O que foi lido de dentro de um APK: manifesto e assinatura. */
data class ApkLido(
    val pacote: String?,
    val versaoNome: String?,
    val codigo: Int?,
    /** SHA-256 (hex minúsculo) do certificado de cada assinante. */
    val certificados: List<String>,
    val assinaturaValida: Boolean,
    val errosAssinatura: List<String>
)

/**
 * Lê um APK com a apksig (a mesma biblioteca que o Android usa): pacote, versionName e versionCode do
 * manifesto binário, e confere a assinatura (v1/v2/v3). Nada do arquivo é executado.
 */
object LeitorApk {

    // Atributos do <manifest> (ids de recurso do Android)
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c

    /** Lança IllegalArgumentException se o arquivo não for um APK. */
    fun ler(arquivo: Path): ApkLido {
        val (pacote, versaoNome, codigo) = try {
            RandomAccessFile(arquivo.toFile(), "r").use { lerManifesto(it) }
        } catch (e: Exception) {
            throw IllegalArgumentException("O arquivo enviado não é um APK válido (${e.message ?: e.javaClass.simpleName})")
        }

        val verificacao = try {
            ApkVerifier.Builder(arquivo.toFile()).build().verify()
        } catch (e: Exception) {
            throw IllegalArgumentException("Não foi possível conferir a assinatura do APK (${e.message ?: e.javaClass.simpleName})")
        }

        return ApkLido(
            pacote = pacote,
            versaoNome = versaoNome,
            codigo = codigo,
            certificados = verificacao.signerCertificates.map { sha256Hex(it.encoded) },
            assinaturaValida = verificacao.isVerified,
            errosAssinatura = verificacao.errors.map { it.toString() }
        )
    }

    private fun lerManifesto(apk: RandomAccessFile): Triple<String?, String?, Int?> {
        val parser = AndroidBinXmlParser(ApkUtils.getAndroidManifest(DataSources.asDataSource(apk)))
        while (parser.eventType != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.eventType == AndroidBinXmlParser.EVENT_START_ELEMENT && parser.depth == 1 && parser.name == "manifest") {
                var pacote: String? = null
                var versaoNome: String? = null
                var codigo: Int? = null
                for (i in 0 until parser.attributeCount) {
                    when {
                        parser.getAttributeNameResourceId(i) == ATTR_VERSION_CODE -> codigo = valorInteiro(parser, i)
                        parser.getAttributeNameResourceId(i) == ATTR_VERSION_NAME -> versaoNome = parser.getAttributeStringValue(i)
                        parser.getAttributeName(i) == "package" && parser.getAttributeNamespace(i).isEmpty() -> pacote = parser.getAttributeStringValue(i)
                    }
                }
                return Triple(pacote, versaoNome, codigo)
            }
            parser.next()
        }
        throw IllegalArgumentException("manifesto sem o elemento <manifest>")
    }

    private fun valorInteiro(parser: AndroidBinXmlParser, i: Int): Int? =
        if (parser.getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_INT) parser.getAttributeIntValue(i)
        else parser.getAttributeStringValue(i).trim().toIntOrNull()

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** "303f97aa..." → "30:3F:97:AA:...", como aparece no keytool e na tela Status. */
    fun formatarImpressao(hex: String): String = hex.uppercase().chunked(2).joinToString(":")

    /** Aceita a impressão com ou sem ":" e em qualquer caixa; devolve hex minúsculo (vazio se não informada). */
    fun normalizarImpressao(texto: String): String = texto.replace(":", "").replace(" ", "").trim().lowercase()
}
