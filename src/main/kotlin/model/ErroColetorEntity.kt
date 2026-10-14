package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Um erro do app num coletor (ErroColetorService). [momento] é quando aconteceu no coletor (pode ter sido
 * offline); [recebidoEm], quando chegou ao servidor. [id] vem do app (UUID): reenvio não duplica.
 */
@Entity
@Table(name = "erros_coletor")
class ErroColetorEntity(

    @Id
    @Column(length = 36)
    val id: String,

    @Column(nullable = false, length = 100)
    val coletor: String,

    @Column(nullable = false)
    val momento: LocalDateTime,

    @Column(nullable = false)
    val recebidoEm: LocalDateTime,

    /** FALHA_GERAL (o app travou), ENVIO_RECUSADO, ERRO_SERVIDOR, LEITOR_RFID, ATUALIZACAO... */
    @Column(nullable = false, length = 30)
    val tipo: String,

    @Column(nullable = false, length = 500)
    val mensagem: String,

    /** Stack trace ou contexto (sem dados sensíveis), cortado em 8000 caracteres. */
    @Column(length = 8000)
    val detalhe: String? = null,

    /** Quantas vezes o mesmo erro aconteceu em sequência no coletor (o app agrupa os repetidos). */
    @Column(nullable = false)
    val ocorrencias: Int = 1,

    @Column(length = 50)
    val versaoApp: String? = null,

    val codigoVersao: Int? = null,

    val operador: String? = null
)
