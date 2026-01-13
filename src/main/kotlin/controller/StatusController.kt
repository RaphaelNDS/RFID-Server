package org.example.controller

import org.example.service.StatusService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/status")
class StatusController(
    private val service: StatusService
) {

    @GetMapping
    fun status() = service.getStatus()
}
