package org.example.controller

import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.security.core.context.SecurityContextHolder

@Controller
class RedirectController {

    @GetMapping("/redirect")
    fun redirect(): String {
        val auth = SecurityContextHolder.getContext().authentication
        val roles = auth.authorities.map { it.authority }

        return when {
            roles.contains("ROLE_ADMIN") -> "redirect:/admin"
            roles.contains("ROLE_GESTOR") -> "redirect:/gestor"
            roles.contains("ROLE_USER") -> "redirect:/user"
            else -> "redirect:/login"
        }
    }
}