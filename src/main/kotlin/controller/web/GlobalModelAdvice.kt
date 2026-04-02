package org.example.controller.web

import org.example.service.StatusService
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

@ControllerAdvice
class GlobalModelAdvice(
    private val statusService: StatusService
) {

    @ModelAttribute("info")
    fun addStatusGlobal() = statusService.getStatus()
}