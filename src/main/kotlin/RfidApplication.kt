package org.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RfidApplication

fun main(args: Array<String>) {
    runApplication<RfidApplication>(*args)
}
