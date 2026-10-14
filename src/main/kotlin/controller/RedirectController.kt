package org.example.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/** Destino depois do login (SucessoLoginHandler): todos os perfis vão para a tela inicial. */
@Controller
class RedirectController {

    @GetMapping("/redirect")
    fun redirect(): String = "redirect:/dashboard"
}
