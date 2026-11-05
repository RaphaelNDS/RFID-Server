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

/* ============================ CONFIRMAÇÃO E AVISOS ============================ */
// No lugar do confirm()/prompt()/alert() do navegador, que destoavam do resto do sistema (e no celular mostram
// o endereço do servidor no título).

/**
 * Abre a janela de confirmação. Devolve uma Promise com null (cancelou), true (confirmou) ou, com `campo`,
 * o texto digitado (obrigatório). Opções: titulo, mensagem, botao, perigo (botão vermelho), campo (rótulo do
 * campo de texto).
 */
function confirmar(opcoes) {
  // Uma janela por vez: a segunda repetiria os ids e os atalhos de teclado da primeira
  if (document.querySelector(".modal-fundo.confirmar")) return Promise.resolve(null);
  return new Promise(function (resolver) {
    const o = opcoes || {};
    const fundo = document.createElement("div");
    fundo.className = "modal-fundo aberto confirmar";
    fundo.innerHTML =
      '<div class="modal" role="alertdialog" aria-modal="true" aria-labelledby="confirmarTitulo" aria-describedby="confirmarMensagem">' +
      '  <div class="modal-cabecalho"><h2 id="confirmarTitulo"></h2></div>' +
      '  <form class="modal-corpo" novalidate>' +
      '    <p id="confirmarMensagem" class="confirmar-mensagem"></p>' +
      '    <div class="field" hidden><label for="confirmarCampo"></label>' +
      '      <input type="text" id="confirmarCampo" maxlength="255" autocomplete="off">' +
      '      <span class="field-erro" hidden>Preencha este campo para continuar.</span></div>' +
      '  </form>' +
      '  <div class="modal-rodape">' +
      '    <button type="button" class="btn btn-ghost" data-acao="cancelar">Cancelar</button>' +
      '    <button type="button" class="btn" data-acao="ok"></button>' +
      '  </div>' +
      '</div>';
    // textContent: nomes de usuário e etiquetas entram como texto, nunca como HTML
    fundo.querySelector("#confirmarTitulo").textContent = o.titulo || "Confirmar";
    fundo.querySelector("#confirmarMensagem").textContent = o.mensagem || "";
    const ok = fundo.querySelector('[data-acao="ok"]');
    ok.textContent = o.botao || "Confirmar";
    ok.classList.add(o.perigo ? "btn-danger" : "btn-primary");
    const campo = fundo.querySelector("#confirmarCampo");
    const erroCampo = fundo.querySelector(".field-erro");
    if (o.campo) {
      campo.closest(".field").hidden = false;
      fundo.querySelector('label[for="confirmarCampo"]').textContent = o.campo;
    }

    const focoAnterior = document.activeElement;
    function fechar(resultado) {
      document.removeEventListener("keydown", teclas, true);
      fundo.remove();
      if (focoAnterior && focoAnterior.focus) focoAnterior.focus();
      resolver(resultado);
    }
    function confirmarAgora() {
      if (!o.campo) return fechar(true);
      const valor = campo.value.trim();
      if (!valor) { erroCampo.hidden = false; campo.focus(); return; }
      fechar(valor);
    }
    function teclas(e) {
      if (e.key === "Escape") { e.stopPropagation(); fechar(null); }
    }

    ok.addEventListener("click", confirmarAgora);
    fundo.querySelector('[data-acao="cancelar"]').addEventListener("click", function () { fechar(null); });
    fundo.querySelector("form").addEventListener("submit", function (e) { e.preventDefault(); confirmarAgora(); });
    // Clique fora fecha, mas só um clique de verdade no fundo: o mouse precisa descer E subir no fundo (arrastar a
    // seleção do motivo até fora da caixa não apaga o texto) e não vale nos primeiros 400 ms (o segundo clique de
    // um duplo clique no botão Excluir caía no fundo e fechava a janela na hora)
    const abertaEm = Date.now();
    let desceuNoFundo = false;
    fundo.addEventListener("mousedown", function (e) { desceuNoFundo = e.target === fundo; });
    fundo.addEventListener("click", function (e) {
      if (e.target === fundo && desceuNoFundo && Date.now() - abertaEm > 400) fechar(null);
    });
    // Tab fica dentro da janela (aria-modal): antes saía para os botões da página e abria uma segunda janela
    fundo.addEventListener("keydown", function (e) {
      if (e.key !== "Tab") return;
      const focaveis = Array.from(fundo.querySelectorAll("button, input")).filter(function (el) { return el.offsetParent !== null; });
      const primeiro = focaveis[0], ultimo = focaveis[focaveis.length - 1];
      if (e.shiftKey && document.activeElement === primeiro) { e.preventDefault(); ultimo.focus(); }
      else if (!e.shiftKey && document.activeElement === ultimo) { e.preventDefault(); primeiro.focus(); }
    });
    document.addEventListener("keydown", teclas, true);

    document.body.appendChild(fundo);
    (o.campo ? campo : ok).focus();
  });
}

/** Aviso que some sozinho no canto da tela (tipo "erro" ou "ok"). */
function avisar(mensagem, tipo) {
  let pilha = document.getElementById("avisos");
  if (!pilha) {
    pilha = document.createElement("div");
    pilha.id = "avisos";
    pilha.className = "avisos";
    pilha.setAttribute("aria-live", "assertive");
    document.body.appendChild(pilha);
  }
  const aviso = document.createElement("div");
  aviso.className = "alert " + (tipo === "ok" ? "alert-ok" : "alert-erro");
  aviso.setAttribute("role", tipo === "ok" ? "status" : "alert");
  aviso.textContent = mensagem;
  pilha.appendChild(aviso);
  setTimeout(function () { aviso.remove(); }, 6000);
}

// Formulário com data-confirmar="mensagem" pede confirmação antes de enviar. Opcionais: data-confirmar-titulo,
// data-confirmar-botao, data-confirmar-perigo (botão vermelho) e data-confirmar-campo="rótulo" +
// data-confirmar-destino="nome do input" (texto obrigatório copiado para esse input, ex.: motivo da retirada)
document.addEventListener("submit", function (e) {
  const form = e.target;
  if (!(form instanceof HTMLFormElement) || !form.dataset.confirmar) return;
  e.preventDefault();
  confirmar({
    titulo: form.dataset.confirmarTitulo,
    mensagem: form.dataset.confirmar,
    botao: form.dataset.confirmarBotao,
    perigo: form.dataset.confirmarPerigo !== undefined,
    campo: form.dataset.confirmarCampo
  }).then(function (resposta) {
    if (resposta === null) return;
    if (form.dataset.confirmarDestino && form.elements[form.dataset.confirmarDestino]) {
      form.elements[form.dataset.confirmarDestino].value = resposta;
    }
    // submit() direto não dispara este evento de novo
    HTMLFormElement.prototype.submit.call(form);
  });
});
// Libera os formulários com data-confirmar (o layout barra o envio deles até esta linha rodar)
window.confirmacaoPronta = true;
