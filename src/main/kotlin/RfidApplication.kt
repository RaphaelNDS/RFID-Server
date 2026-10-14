package org.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@SpringBootApplication
@EnableScheduling // tarefas agendadas, como o backup diário do banco
class RfidApplication {

    // Relógio injetável: nos testes dá para "avançar o tempo" (ex.: fim do bloqueio de login)
    @Bean
    fun relogio(): Clock = Clock.systemDefaultZone()
}

fun main(args: Array<String>) {
    runApplication<RfidApplication>(*args)
}
