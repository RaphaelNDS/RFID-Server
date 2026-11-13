package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Avisos por email (1.5.1) e pelo Telegram (1.20.0, [TelegramService]): backup do banco com problema, cópia fora do computador falhando e servidor que ficou
 * fora do ar. Antes os avisos ficavam só no log e na tela Status, e ninguém era avisado de fora.
 *
 * Liga só com o SMTP configurado (spring.mail.host = RFID_SMTP_HOST) e pelo menos um destinatário
 * (rfid.alertas.email-para = RFID_ALERTAS_EMAIL). Desligado, os avisos continuam no log, como antes.
 *
 * Contra caixa de entrada lotada: cada problema ([problema], pela [chave]) manda um email quando aparece e repete no
 * máximo a cada [repetirHoras] enquanto continuar; quando some ([resolvido]), manda um "resolvido". O envio é numa
 * linha à parte: o SMTP lento ou fora do ar não segura o servidor.
 */
@Service
class AlertaService(
    private val mail: ObjectProvider<JavaMailSender>,
    @Value("\${spring.mail.host:}") private val host: String,
    @Value("\${spring.mail.username:}") private val usuario: String,
    @Value("\${rfid.alertas.email-para:}") para: String,
    @Value("\${rfid.alertas.email-de:}") de: String,
    @Value("\${rfid.alertas.repetir-horas:24}") private val repetirHoras: Long,
    private val relogio: Clock,
    // 1.20.0: o mesmo aviso também pelo Telegram (liga sozinho, sem precisar do email)
    private val telegram: TelegramService? = null
) {

    private val log = LoggerFactory.getLogger(javaClass)

    val destinatarios: List<String> = para.split(',', ';').map { it.trim() }.filter { it.contains('@') }.distinct()
    private val remetente: String = de.trim().ifEmpty { usuario.trim() }

    val ligado: Boolean get() = host.isNotBlank() && destinatarios.isNotEmpty() && remetente.contains('@') && mail.ifAvailable != null

    /** Problemas em aberto: chave → quando o último email dele foi mandado. */
    private val abertos = ConcurrentHashMap<String, LocalDateTime>()

    @Volatile private var ultimoEnvio: LocalDateTime? = null
    @Volatile private var ultimoErro: String? = null

    /** 1.20.0: Telegram ligado (token e conversas). */
    val telegramLigado: Boolean get() = telegram?.ligado == true

    /** 1.20.0: Telegram com o token, mesmo sem conversa (dá para descobrir as conversas na tela Status). */
    val telegramConfigurado: Boolean get() = telegram?.configurado == true

    @Volatile private var ultimoEnvioTelegram: LocalDateTime? = null
    @Volatile private var ultimoErroTelegram: String? = null

    private val envio = Executors.newSingleThreadExecutor { Thread(it, "avisos-email").apply { isDaemon = true } }

    /** Avisa um problema (no máximo um email a cada [repetirHoras] enquanto ele continuar). */
    fun problema(chave: String, assunto: String, texto: String) {
        val agora = LocalDateTime.now(relogio)
        val anterior = abertos[chave]
        if (anterior != null && Duration.between(anterior, agora).toHours() < repetirHoras) return
        abertos[chave] = agora
        log.warn("Aviso ({}): {} — {}", chave, assunto, texto.lineSequence().first())
        enviar(assunto, texto, if (anterior == null) "" else "Continua desde o aviso anterior.\n\n")
    }

    /** O problema [chave] sumiu: manda um "resolvido" só se ele tinha sido avisado. */
    fun resolvido(chave: String, assunto: String, texto: String) {
        if (abertos.remove(chave) == null) return
        log.info("Aviso ({}) resolvido: {}", chave, assunto)
        enviar("Resolvido: $assunto", texto, "")
    }

    /** Aviso único, sem repetição (ex.: o servidor ficou fora do ar e voltou). */
    fun informar(assunto: String, texto: String) {
        log.warn("Aviso: {} — {}", assunto, texto.lineSequence().first())
        enviar(assunto, texto, "")
    }

    /** Email de teste da tela Status: na hora (não na fila), para o ADMIN ver o erro do SMTP, se houver. */
    fun enviarTeste(pedidoPor: String): Result<List<String>> {
        if (!ligado) return Result.failure(IllegalStateException(motivoDesligado()))
        return runCatching {
            mandar("Email de teste", "Teste pedido por $pedidoPor na tela Status do servidor RFID. Se chegou, os avisos estão funcionando.")
            log.info("Email de teste dos avisos enviado para {} (pedido por {})", destinatarios.joinToString(), pedidoPor)
            destinatarios
        }.onFailure { registrarErro(it) }
    }

    /** Texto da tela Status. */
    fun situacao(): String {
        if (!ligado) return "desligados: ${motivoDesligado()}"
        return buildString {
            append("ligados, para ${destinatarios.joinToString()}")
            ultimoEnvio?.let { append(" (último envio em ${it.format(FORMATO)})") }
            ultimoErro?.let { append(" — o último envio FALHOU: $it") }
        }
    }

    val comProblema: Boolean get() = (ligado && ultimoErro != null) || (telegramLigado && ultimoErroTelegram != null)

    /** 1.20.0: texto da tela Status para o Telegram. */
    fun situacaoTelegram(): String {
        val t = telegram
        if (t == null || !t.ligado) return "desligados: ${t?.motivoDesligado() ?: "indisponível"}"
        return buildString {
            append("ligados, para ${t.conversas.size} conversa(s)")
            ultimoEnvioTelegram?.let { append(" (último envio em ${it.format(FORMATO)})") }
            ultimoErroTelegram?.let { append(" — o último envio FALHOU: $it") }
        }
    }

    /** 1.20.0: mensagem de teste pelo Telegram, na hora, para o ADMIN ver o erro, se houver. */
    fun enviarTesteTelegram(pedidoPor: String): Result<Int> {
        val t = telegram
        if (t == null || !t.ligado) return Result.failure(IllegalStateException(t?.motivoDesligado() ?: "Telegram indisponível"))
        return runCatching {
            mandarTelegram("Mensagem de teste", "Teste pedido por $pedidoPor na tela Status do servidor RFID. Se chegou, os avisos pelo Telegram estão funcionando.")
            log.info("Mensagem de teste dos avisos enviada pelo Telegram para {} conversa(s) (pedido por {})", t.conversas.size, pedidoPor)
            t.conversas.size
        }.onFailure { registrarErroTelegram(it) }
    }

    /** 1.20.0: quem mandou mensagem ao robô, para achar o número da conversa. */
    fun descobrirConversasTelegram(): Result<List<ConversaTelegram>> {
        val t = telegram ?: return Result.failure(IllegalStateException("Telegram indisponível"))
        return runCatching { t.descobrirConversas() }
    }

    private fun motivoDesligado(): String = when {
        host.isBlank() -> "defina o servidor de email (RFID_SMTP_HOST, RFID_SMTP_USUARIO, RFID_SMTP_SENHA) e os destinatários (RFID_ALERTAS_EMAIL)"
        destinatarios.isEmpty() -> "defina os destinatários em RFID_ALERTAS_EMAIL"
        !remetente.contains('@') -> "defina o remetente em RFID_ALERTAS_DE (ou o usuário do SMTP como email)"
        else -> "envio de email indisponível"
    }

    private fun enviar(assunto: String, texto: String, prefixo: String) {
        val email = ligado
        val tg = telegramLigado
        if (!email && !tg) return
        envio.execute {
            if (email) {
                runCatching { mandar(assunto, prefixo + texto) }
                    .onSuccess { log.info("Aviso por email enviado para {}: {}", destinatarios.joinToString(), assunto) }
                    .onFailure { registrarErro(it) }
            }
            if (tg) {
                runCatching { mandarTelegram(assunto, prefixo + texto) }
                    .onSuccess { log.info("Aviso pelo Telegram enviado: {}", assunto) }
                    .onFailure { registrarErroTelegram(it) }
            }
        }
    }

    private fun mandarTelegram(assunto: String, texto: String) {
        val agora = LocalDateTime.now(relogio)
        telegram!!.enviar("⚠️ Servidor RFID: $assunto\n\n$texto\n\n${agora.format(FORMATO)}")
        ultimoEnvioTelegram = agora
        ultimoErroTelegram = null
    }

    private fun registrarErroTelegram(e: Throwable) {
        ultimoErroTelegram = e.message?.lineSequence()?.firstOrNull()?.take(200) ?: e.javaClass.simpleName
        log.warn("Aviso pelo Telegram não foi enviado: {}", ultimoErroTelegram)
    }

    private fun mandar(assunto: String, texto: String) {
        val agora = LocalDateTime.now(relogio)
        val mensagem = SimpleMailMessage().apply {
            from = remetente
            setTo(*destinatarios.toTypedArray())
            subject = "[Servidor RFID] $assunto"
            text = "$texto\n\n— Servidor RFID, ${agora.format(FORMATO)}. Detalhes na tela Status e no log (data\\logs\\rfid-server.log)."
        }
        mail.getObject().send(mensagem)
        ultimoEnvio = agora
        ultimoErro = null
    }

    private fun registrarErro(e: Throwable) {
        ultimoErro = e.message?.lineSequence()?.firstOrNull()?.take(200) ?: e.javaClass.simpleName
        log.warn("Aviso por email não foi enviado: {}", ultimoErro)
    }

    private companion object {
        val FORMATO: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    }
}
