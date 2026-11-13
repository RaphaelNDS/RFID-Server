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
    var ultimoOperador: String? = null,

    // Diagnóstico mandado pelo app 1.7.x em toda requisição (ver DiagnosticoColetor). Último valor recebido;
    // nulo = o app não mandou (anterior à 1.7)

    /** Registros esperando envio na fila do coletor (leituras, etiquetas de inventário, cadastros). */
    var filaPendentes: Int? = null,
    /** Hora (do coletor) da leitura pendente mais antiga; nula sem leitura pendente. */
    var filaMaisAntiga: LocalDateTime? = null,
    /** Última sincronização completa (hora do coletor). */
    var ultimaSincOk: LocalDateTime? = null,
    @Column(name = "espaco_livre_mb")
    var espacoLivreMb: Long? = null,
    @Column(length = 20)
    var versaoAndroid: String? = null,
    @Column(length = 100)
    var modelo: String? = null,
    /** Hora do coletor menos a do servidor, em segundos (positivo = coletor adiantado). */
    var desvioRelogioSegundos: Long? = null,

    /** 1.31.0 (V42): em treinamento, as leituras e os inventários deste coletor não mudam nada de verdade. */
    @Column(nullable = false)
    var treinamento: Boolean = false
)
