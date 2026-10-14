package org.example.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController {

    // Raiz do site: vai direto para a tela inicial (quem não estiver logado é levado ao login)
    @GetMapping("/")
    fun home(): String = "redirect:/dashboard"
}
