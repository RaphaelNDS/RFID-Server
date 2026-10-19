package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Uma versão do app Android publicada pelo servidor. Pacote, versão e assinatura foram lidos de dentro do
 * APK no envio (AtualizacaoAppService); [arquivo] é o nome gerado pelo servidor dentro de rfid.app.pasta.
 */
@Entity
@Table(name = "versoes_app")
class VersaoAppEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 50)
    val versaoNome: String,

    @Column(nullable = false, unique = true)
    val codigo: Int,

    @Column(nullable = false)
    val arquivo: String,

    @Column(nullable = false)
    val tamanho: Long,

    @Column(nullable = false, length = 64)
    val sha256: String,

    @Column(name = "certificado_sha256", nullable = false, length = 64)
    val certificadoSha256: String,

    @Column(length = 1000)
    val notas: String? = null,

    @Column(nullable = false)
    val publicadaEm: LocalDateTime,

    @Column(nullable = false)
    val publicadaPor: String,

    /** O arquivo foi apagado para economizar disco (só as últimas versões ficam guardadas). */
    var arquivoRemovido: Boolean = false
)
