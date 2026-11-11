package org.example.service

import org.example.model.AnexoBemConteudoEntity
import org.example.model.AnexoBemEntity
import org.example.model.TagEntity
import org.example.model.TipoAnexo
import org.example.repository.AnexoBemConteudoRepository
import org.example.repository.AnexoBemRepository
import org.example.repository.TagRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/** Um arquivo pronto para mandar ao navegador. */
class ArquivoAnexo(val anexo: AnexoBemEntity, val conteudo: ByteArray)

/**
 * Fotos e documentos do bem (1.9.0): foto do equipamento, nota fiscal, laudo. Aceita só JPEG, PNG, WebP e PDF,
 * reconhecidos pelos primeiros bytes; até [maximoBytes] por arquivo e [maximoPorBem] por bem. Os bytes ficam no
 * banco (V26), então o backup do banco já os leva. Enviar e excluir vão para o histórico da etiqueta.
 */
@Service
class AnexoBemService(
    private val anexos: AnexoBemRepository,
    private val conteudos: AnexoBemConteudoRepository,
    private val tags: TagRepository,
    private val historico: HistoricoEtiquetaService,
    private val relogio: Clock,
    @Value("\${rfid.anexos.max-bytes:10485760}") val maximoBytes: Long = 10L * 1024 * 1024,
    @Value("\${rfid.anexos.max-por-bem:20}") val maximoPorBem: Int = 20
) {

    private val log = LoggerFactory.getLogger(javaClass)

    val maximoRotulo: String get() = "${maximoBytes / (1024 * 1024)} MB"

    fun listar(tagId: Long): List<AnexoBemEntity> = anexos.findAllByTagIdOrderByIdAsc(tagId)

    @Transactional
    fun adicionar(tagId: Long, nomeOriginal: String?, bytes: ByteArray, descricao: String?): AnexoBemEntity {
        val tag = etiqueta(tagId)
        require(bytes.isNotEmpty()) { "Escolha o arquivo (foto ou PDF)" }
        require(bytes.size <= maximoBytes) { "O arquivo tem ${bytes.size / (1024 * 1024)} MB: o máximo é $maximoRotulo" }
        val tipo = TipoAnexo.detectar(bytes)
            ?: throw IllegalArgumentException("Tipo de arquivo não aceito: envie uma foto (JPEG, PNG ou WebP) ou um PDF")
        val quantos = anexos.countByTagId(tagId)
        require(quantos < maximoPorBem) { "Este bem já tem $quantos anexos, o máximo. Exclua um para enviar outro." }

        val nome = nomeSeguro(nomeOriginal, tipo)
        val (usuario, _) = historico.autor()
        val anexo = anexos.save(AnexoBemEntity(
            tagId = tagId, nome = nome, tipo = tipo, tamanhoBytes = bytes.size,
            descricao = descricao?.trim()?.take(200)?.ifEmpty { null },
            enviadoEm = LocalDateTime.now(relogio), enviadoPor = usuario.take(255)
        ))
        conteudos.save(AnexoBemConteudoEntity(anexo.id, bytes))
        historico.registrarAnexo(tag, null, "${anexo.nome} (${anexo.tamanhoRotulo})")
        log.info("Anexo {} ({}, {}) enviado para a etiqueta {} por {}", nome, tipo, anexo.tamanhoRotulo, tag.tagReal, usuario)
        return anexo
    }

    @Transactional(readOnly = true)
    fun arquivo(tagId: Long, anexoId: Long): ArquivoAnexo {
        val anexo = anexoDaEtiqueta(tagId, anexoId)
        val conteudo = conteudos.findById(anexo.id).orElseThrow { NoSuchElementException("Arquivo do anexo $anexoId não encontrado") }
        return ArquivoAnexo(anexo, conteudo.conteudo)
    }

    @Transactional
    fun excluir(tagId: Long, anexoId: Long): AnexoBemEntity {
        val tag = etiqueta(tagId)
        val anexo = anexoDaEtiqueta(tagId, anexoId)
        conteudos.deleteById(anexo.id)
        anexos.delete(anexo)
        historico.registrarAnexo(tag, "${anexo.nome} (${anexo.tamanhoRotulo})", null)
        log.warn("Anexo {} da etiqueta {} excluído por {}", anexo.nome, tag.tagReal, historico.autor().first)
        return anexo
    }

    private fun etiqueta(tagId: Long): TagEntity =
        tags.findById(tagId).orElseThrow { NoSuchElementException("Etiqueta $tagId não encontrada") }

    /** O anexo precisa ser DESTA etiqueta: o id na URL não abre o anexo de outro bem. */
    private fun anexoDaEtiqueta(tagId: Long, anexoId: Long): AnexoBemEntity =
        anexos.findById(anexoId).filter { it.tagId == tagId }
            .orElseThrow { NoSuchElementException("Anexo $anexoId não encontrado nesta etiqueta") }

    /** Só o nome (sem pasta), sem caracteres de controle, até 150 caracteres e com a extensão do tipo real. */
    private fun nomeSeguro(original: String?, tipo: TipoAnexo): String {
        val base = original.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() && it !in "\"<>|*?:" }.trim()
            .substringBeforeLast('.').ifBlank { "anexo" }.take(140)
        return "$base.${tipo.extensao}"
    }
}
