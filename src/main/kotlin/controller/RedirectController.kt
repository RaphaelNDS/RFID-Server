package org.example.controller

import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.security.core.context.SecurityContextHolder

//@Controller
//class RedirectController {
//
//    @GetMapping("/redirect")
//    fun redirect(): String {
//
//        val auth = SecurityContextHolder.getContext().authentication
//            ?: return "redirect:/login"
//
//        val roles = auth.authorities.map { it.authority }
//
//        return when {
//            "ROLE_ADMIN" in roles -> "redirect:/admin"
//            "ROLE_GESTOR" in roles -> "redirect:/gestor"
//            "ROLE_USER" in roles -> "redirect:/user"
//            else -> "redirect:/login"
//        }
//    }
//}


@Controller
class RedirectController {

    @GetMapping("/redirect")
    fun redirect(): String {
        return "redirect:/dashboard"
    }
}