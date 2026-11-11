package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Tipo do anexo, reconhecido pelos primeiros bytes do arquivo (não pela extensão nem pelo que o navegador diz).
 * Só estes quatro: o arquivo é mostrado no navegador com este Content-Type, então nada que possa virar página.
 */
enum class TipoAnexo(val contentType: String, val extensao: String, val imagem: Boolean, val rotulo: String) {
    JPEG("image/jpeg", "jpg", true, "Foto JPEG"),
    PNG("image/png", "png", true, "Imagem PNG"),
    WEBP("image/webp", "webp", true, "Imagem WebP"),
    PDF("application/pdf", "pdf", false, "PDF");

    companion object {
        /** Reconhece pelo começo do arquivo; null = tipo não aceito. */
        fun detectar(b: ByteArray): TipoAnexo? = when {
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> JPEG
            b.size >= 8 && b.copyOf(8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> PNG
            b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> WEBP
            b.size >= 5 && String(b, 0, 5, Charsets.US_ASCII) == "%PDF-" -> PDF
            else -> null
        }
    }
}

/** Foto ou documento de um bem (1.9.0, V26). Os bytes ficam em [AnexoBemConteudoEntity], lidos só ao abrir o arquivo. */
@Entity
@Table(name = "anexos_bem")
class AnexoBemEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "tag_id", nullable = false)
    val tagId: Long,

    @Column(nullable = false, length = 150)
    val nome: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val tipo: TipoAnexo,

    @Column(name = "tamanho_bytes", nullable = false)
    val tamanhoBytes: Int,

    @Column(length = 200)
    val descricao: String? = null,

    @Column(name = "enviado_em", nullable = false)
    val enviadoEm: LocalDateTime,

    /** Email de quem enviou. */
    @Column(name = "enviado_por", nullable = false)
    val enviadoPor: String
) {
    val imagem: Boolean get() = tipo.imagem

    /** "1,2 MB", "350 KB". */
    val tamanhoRotulo: String get() = when {
        tamanhoBytes >= 1024 * 1024 -> String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f MB", tamanhoBytes / (1024.0 * 1024.0))
        tamanhoBytes >= 1024 -> "${tamanhoBytes / 1024} KB"
        else -> "$tamanhoBytes bytes"
    }
}

@Entity
@Table(name = "anexos_bem_conteudo")
class AnexoBemConteudoEntity(
    @Id
    @Column(name = "anexo_id")
    val anexoId: Long,

    @Column(nullable = false, columnDefinition = "bytea")
    val conteudo: ByteArray
)
