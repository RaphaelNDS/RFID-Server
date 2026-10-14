package org.example.service

import org.example.model.VersaoAppEntity
import org.example.repository.VersaoAppRepository
import org.example.util.LeitorApk
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Atualização do app dos coletores pelo servidor: o ADMIN publica um APK na web e os coletores baixam.
 *
 * Isto distribui código para todos os coletores, então o APK só é aceito se:
 *  - for um APK de verdade, com a assinatura válida (apksig, a mesma conferência do Android);
 *  - for do pacote do app ([pacoteEsperado]);
 *  - estiver assinado com a chave da empresa: impressão digital fixada em rfid.app.certificado-sha256.
 *    Sem ela configurada, a publicação fica desligada (não se confia no primeiro arquivo enviado);
 *  - tiver código de versão MAIOR que o de todas as já publicadas (o Android não instala versão menor).
 *
 * O arquivo é gravado primeiro como temporário e só vai para o nome final (gerado aqui, nunca o nome
 * enviado) depois de conferido. Só as últimas [versoesGuardadas] versões mantêm o arquivo.
 */
@Service
class AtualizacaoAppService(
    private val repo: VersaoAppRepository,
    private val relogio: Clock,
    @Value("\${rfid.app.pasta:./data/app}") pasta: String,
    @Value("\${rfid.app.pacote:br.com.bkpbr.rfidandroid}") val pacoteEsperado: String,
    @Value("\${rfid.app.certificado-sha256:}") certificado: String,
    @Value("\${rfid.app.versoes-guardadas:3}") versoesGuardadas: Int
) {

    private val log = LoggerFactory.getLogger(javaClass)

    val pasta: Path = Paths.get(pasta).toAbsolutePath().normalize()
    val certificadoEsperado: String = LeitorApk.normalizarImpressao(certificado)
    val versoesGuardadas: Int = versoesGuardadas.coerceAtLeast(1)

    val publicacaoLigada: Boolean get() = certificadoEsperado.length == 64

    // Para logar uma vez por coletor e versão (e não a cada consulta/download repetido)
    private val avisados = ConcurrentHashMap.newKeySet<String>()

    init {
        if (!publicacaoLigada) {
            log.warn("Atualização do app pelo servidor DESLIGADA: configure RFID_APP_CERTIFICADO_SHA256 com a impressão digital " +
                "SHA-256 do certificado da chave de assinatura do app (keytool -list -v)")
        }
    }

    /** Recebe, confere e publica um APK. Lança IllegalArgumentException/IllegalStateException com a mensagem para a tela. */
    @Synchronized
    fun publicar(entrada: InputStream, notas: String?, usuario: String): VersaoAppEntity {
        check(publicacaoLigada) {
            "Publicação desligada: configure RFID_APP_CERTIFICADO_SHA256 com a impressão digital do certificado da chave do app"
        }

        Files.createDirectories(pasta)
        val temporario = pasta.resolve("envio-${UUID.randomUUID()}.parcial")
        // Arquivo final já no lugar, mas a versão ainda não gravada no banco: apagado se algo falhar
        var arquivoSemRegistro: Path? = null

        try {
            val tamanho = copiarComLimite(entrada, temporario)
            val apk = LeitorApk.ler(temporario)
            val (versaoNome, codigo) = conferir(apk, usuario)

            val sha256 = sha256Arquivo(temporario)
            val nomeArquivo = "rfid-android-${versaoNome.replace(Regex("[^A-Za-z0-9._-]"), "_")}-$codigo.apk"
            Files.move(temporario, pasta.resolve(nomeArquivo), StandardCopyOption.REPLACE_EXISTING)
            arquivoSemRegistro = pasta.resolve(nomeArquivo)

            val versao = repo.saveAndFlush(
                VersaoAppEntity(
                    versaoNome = versaoNome, codigo = codigo, arquivo = nomeArquivo, tamanho = tamanho, sha256 = sha256,
                    certificadoSha256 = apk.certificados.single(),
                    notas = notas?.trim()?.take(1000)?.ifBlank { null },
                    publicadaEm = LocalDateTime.now(relogio), publicadaPor = usuario
                )
            )
            arquivoSemRegistro = null

            log.info("Versão {} (código {}) do app publicada por {}: {} bytes, SHA-256 {}, certificado {}",
                versaoNome, codigo, usuario, tamanho, sha256, LeitorApk.formatarImpressao(versao.certificadoSha256))
            removerArquivosAntigos()
            return versao
        } catch (e: Exception) {
            Files.deleteIfExists(temporario)
            arquivoSemRegistro?.let {
                Files.deleteIfExists(it)
                log.error("Falha ao registrar a versão publicada por {}: arquivo {} removido para não ficar órfão", usuario, it.fileName, e)
            }
            if (e is IllegalArgumentException || e is IllegalStateException) {
                log.warn("APK enviado por {} recusado: {}", usuario, e.message)
            }
            throw e
        }
    }

    private fun conferir(apk: org.example.util.ApkLido, usuario: String): Pair<String, Int> {
        require(apk.assinaturaValida) {
            "A assinatura do APK não confere (arquivo corrompido, alterado ou sem assinatura): ${apk.errosAssinatura.take(2).joinToString("; ")}"
        }
        require(apk.pacote == pacoteEsperado) {
            "O APK é do pacote '${apk.pacote}', e não do app dos coletores ($pacoteEsperado)"
        }
        require(apk.certificados.size == 1 && apk.certificados.single() == certificadoEsperado) {
            "O APK foi assinado com outra chave (certificado ${apk.certificados.joinToString { LeitorApk.formatarImpressao(it) }}). " +
                "Só é aceito o APK assinado com a chave da empresa (${LeitorApk.formatarImpressao(certificadoEsperado)})"
        }

        val codigo = apk.codigo ?: throw IllegalArgumentException("O APK não informa o código de versão (versionCode)")
        val versaoNome = apk.versaoNome?.trim()?.takeIf { it.isNotEmpty() && it.length <= 50 }
            ?: throw IllegalArgumentException("O APK não informa o nome da versão (versionName) ou ele é longo demais")

        repo.findFirstByOrderByCodigoDesc()?.let { ultima ->
            require(codigo > ultima.codigo) {
                "A versão $versaoNome (código $codigo) não é mais nova que a já publicada ${ultima.versaoNome} (código ${ultima.codigo}): " +
                    "o Android não instala uma versão menor por cima. Gere o APK de novo, com um código maior"
            }
        }
        return versaoNome to codigo
    }

    // Grava o envio no disco parando no limite (não confia no tamanho informado pelo navegador)
    private fun copiarComLimite(entrada: InputStream, destino: Path): Long {
        var total = 0L
        Files.newOutputStream(destino).use { saida ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val lidos = entrada.read(buffer)
                if (lidos < 0) break
                total += lidos
                require(total <= TAMANHO_MAXIMO) { "O arquivo passa do limite de ${TAMANHO_MAXIMO / (1024 * 1024)} MB" }
                saida.write(buffer, 0, lidos)
            }
        }
        require(total > 0) { "O arquivo enviado está vazio" }
        return total
    }

    /**
     * Apaga os arquivos das versões além das [versoesGuardadas] mais novas. Nunca derruba a publicação: se um
     * arquivo não puder ser apagado (no Windows, um coletor baixando aquele APK no momento), fica para a próxima.
     */
    private fun removerArquivosAntigos() {
        repo.findAllByOrderByCodigoDesc().filter { !it.arquivoRemovido }.drop(versoesGuardadas).forEach { antiga ->
            try {
                Files.deleteIfExists(pasta.resolve(antiga.arquivo))
                antiga.arquivoRemovido = true
                repo.save(antiga)
                log.info("Arquivo da versão {} (código {}) do app removido do disco: só as {} últimas versões ficam guardadas",
                    antiga.versaoNome, antiga.codigo, versoesGuardadas)
            } catch (e: Exception) {
                log.warn("Não foi possível apagar o arquivo da versão antiga {} (código {}) do app: {}; tenta de novo na próxima publicação",
                    antiga.versaoNome, antiga.codigo, e.message)
            }
        }
    }

    /** A versão que os coletores devem ter, ou null se nenhuma foi publicada (ou o arquivo sumiu do disco). */
    fun versaoPublicada(): VersaoAppEntity? {
        val versao = repo.findFirstByArquivoRemovidoFalseOrderByCodigoDesc() ?: return null
        if (!Files.isRegularFile(pasta.resolve(versao.arquivo))) {
            if (avisados.add("sumiu:${versao.codigo}")) {
                log.error("O arquivo da versão publicada {} (código {}) não está em {}: os coletores não vão conseguir atualizar. Publique o APK de novo",
                    versao.versaoNome, versao.codigo, pasta)
            }
            return null
        }
        return versao
    }

    /** Consulta do coletor: devolve a versão publicada e registra no log (uma vez) quem está desatualizado. */
    fun consultar(coletor: String, codigoInstalado: Int?): VersaoAppEntity? {
        val publicada = versaoPublicada() ?: return null
        if (codigoInstalado != null && codigoInstalado < publicada.codigo && avisados.add("consulta:$coletor:${publicada.codigo}")) {
            log.info("Coletor {} está com o código de versão {}: atualização {} (código {}) disponível",
                coletor, codigoInstalado, publicada.versaoNome, publicada.codigo)
        }
        return publicada
    }

    /** Arquivo da versão publicada para o coletor baixar (registra no log uma vez por coletor e versão). */
    fun abrirParaDownload(coletor: String): Pair<VersaoAppEntity, Path>? {
        val publicada = versaoPublicada() ?: return null
        if (avisados.add("download:$coletor:${publicada.codigo}")) {
            log.info("Coletor {} baixou a versão {} (código {}) do app", coletor, publicada.versaoNome, publicada.codigo)
        }
        return publicada to pasta.resolve(publicada.arquivo)
    }

    fun historico(): List<VersaoAppEntity> = repo.findAllByOrderByCodigoDesc()

    private fun sha256Arquivo(arquivo: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(arquivo).use { entrada ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val lidos = entrada.read(buffer)
                if (lidos < 0) break
                digest.update(buffer, 0, lidos)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TAMANHO_MAXIMO = 100L * 1024 * 1024
        const val TIPO_APK = "application/vnd.android.package-archive"
    }
}
