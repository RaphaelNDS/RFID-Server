// Comportamentos comuns a todas as telas: tema, menu no celular, menu do usuário e item ativo do menu.

function alternarTema() {
  const novo = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
  document.documentElement.setAttribute("data-theme", novo);
  try { localStorage.setItem("theme", novo); } catch (e) { }
  document.dispatchEvent(new CustomEvent("tema-alterado", { detail: novo }));
}

document.addEventListener("DOMContentLoaded", function () {

  // Destaca no menu a seção atual (o link mais específico que bate com a URL)
  const links = Array.from(document.querySelectorAll(".nav a[href]"));
  const caminho = window.location.pathname;
  let melhor = null;
  links.forEach(function (a) {
    const href = a.getAttribute("href");
    if (caminho === href || caminho.startsWith(href + "/")) {
      if (!melhor || href.length > melhor.getAttribute("href").length) melhor = a;
    }
  });
  if (melhor) {
    melhor.classList.add("ativo");
    melhor.setAttribute("aria-current", "page");
  }

  // Menu no celular
  const botaoMenu = document.getElementById("menuToggle");
  const nav = document.getElementById("menuPrincipal");
  if (botaoMenu && nav) {
    botaoMenu.addEventListener("click", function (e) {
      e.stopPropagation();
      const aberto = nav.classList.toggle("aberto");
      botaoMenu.setAttribute("aria-expanded", aberto);
    });
  }

  // Menu do usuário
  const perfil = document.getElementById("menuPerfil");
  const botaoPerfil = document.getElementById("menuPerfilBtn");
  if (perfil && botaoPerfil) {
    botaoPerfil.addEventListener("click", function (e) {
      e.stopPropagation();
      const aberto = perfil.classList.toggle("aberto");
      botaoPerfil.setAttribute("aria-expanded", aberto);
    });
  }

  // Clique fora ou Esc fecha os menus
  function fecharMenus() {
    if (perfil) { perfil.classList.remove("aberto"); botaoPerfil.setAttribute("aria-expanded", "false"); }
    if (nav) { nav.classList.remove("aberto"); botaoMenu && botaoMenu.setAttribute("aria-expanded", "false"); }
  }
  document.addEventListener("click", function (e) {
    if (perfil && perfil.contains(e.target)) return;
    if (nav && nav.contains(e.target)) return;
    fecharMenus();
  });
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") fecharMenus();
  });
});
