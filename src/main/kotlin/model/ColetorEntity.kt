package org.example.model

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * Último contato aceito de um coletor Android em /api/android (ColetorService). [nome] é o nome da chave
 * do coletor em rfid.dispositivos.chaves. Versão e código vêm do app (headers X-Versao-App e
 * X-Versao-Codigo); apps anteriores à 1.4.0 não mandam e ficam sem versão.
 */
@Entity
@Table(name = "coletores")
class ColetorEntity(

    @Id
    @Column(length = 100)
    val nome: String,

    @Column(nullable = false)
    val primeiroContato: LocalDateTime,

    @Column(nullable = false)
    var ultimoContato: LocalDateTime,

    @Column(length = 64)
    var ultimoIp: String? = null,

    @Column(length = 50)
    var versaoApp: String? = null,

    var codigoVersao: Int? = null,

    /** Último operador logado que usou o coletor (continua aparecendo depois do "Sair"). */
    var ultimoOperador: String? = null
)
