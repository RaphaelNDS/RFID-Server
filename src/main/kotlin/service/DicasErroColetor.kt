package org.example.service

import org.example.model.ErroColetorEntity

/**
 * 1.30.0: o que fazer diante dos erros conhecidos do coletor. O app manda a mensagem técnica (muitas vezes do leitor
 * Zebra, em inglês); a tela de erros mostra, acima dela, a dica em português para quem está no depósito. A primeira
 * regra que casar vale: a ordem importa ("Charging in Progress" também traz OPERACAO_EM_ANDAMENTO).
 */
object DicasErroColetor {

    private class Regra(val dica: String, vararg padroes: String) {
        val regex = Regex(padroes.joinToString("|"), RegexOption.IGNORE_CASE)
    }

    private val REGRAS = listOf(
        Regra("O coletor estava no carregador (berço ou cabo): tire do carregador para ler as etiquetas.",
            "Charging in Progress", "charging"),
        Regra("Bateria fraca no leitor: carregue o coletor antes de continuar.",
            "battery.{0,20}low", "low.{0,20}battery", "BATTERY_LOW", "bateria fraca"),
        Regra("O leitor RFID esquentou: deixe o coletor descansar alguns minutos e tente de novo.",
            "temperature", "TEMPERATURA", "overheat"),
        Regra("A região de frequência do leitor não está configurada: configure a região Brasil no app do leitor (123RFID) ou peça ajuda ao suporte.",
            "region not", "REGION_NOT", "regulatory"),
        Regra("O leitor RFID não está conectado ao coletor: feche e abra o app; se continuar, reinicie o coletor.",
            "not connected", "NOT_CONNECTED", "reader not available", "no reader", "RFID_READER", "InvalidUsageException", "desconectado"),
        Regra("Uma leitura ainda estava em andamento: solte o gatilho, espere um segundo e aperte de novo.",
            "OPERACAO_EM_ANDAMENTO", "operation in progress", "Command Not allowed"),
        Regra("O coletor não confia no certificado do servidor: cadastre o certificado no coletor (Manual de conexão).",
            "CertPathValidator", "SSLHandshake", "Trust anchor", "certificate", "certificado"),
        Regra("O coletor não alcançou o servidor: confira o Wi-Fi do coletor e se o servidor está ligado.",
            "Failed to connect", "Connection refused", "Unable to resolve host", "UnknownHost", "timeout", "timed out",
            "Network is unreachable", "sem conexão"),
        Regra("Espaço cheio no coletor: apague fotos ou arquivos antigos do aparelho.",
            "No space left", "ENOSPC", "espaço insuficiente")
    )

    /** A dica do erro, ou null quando não há uma conhecida. O relógio errado tem tipo próprio. */
    fun dica(tipo: String?, mensagem: String?, detalhe: String? = null): String? {
        if (tipo == ErroColetorService.TIPO_RELOGIO) return "Acerte a data, a hora e o fuso horário do coletor (Configurações do Android)."
        val texto = listOfNotNull(mensagem, detalhe?.take(2_000)).joinToString("\n")
        if (texto.isBlank()) return null
        return REGRAS.firstOrNull { it.regex.containsMatchIn(texto) }?.dica
    }

    fun dica(erro: ErroColetorEntity): String? = dica(erro.tipo, erro.mensagem, erro.detalhe)
}
