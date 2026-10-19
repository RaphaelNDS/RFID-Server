# RFID Server

Servidor do sistema de controle de equipamentos por etiquetas RFID. Ele guarda o cadastro das etiquetas
(qual equipamento cada etiqueta identifica), recebe as leituras e os inventários feitos pelos coletores
Android e oferece as telas web de administração e relatórios.

O app dos coletores fica em outro repositório: **RFID-SERVER-ANDROID** (pasta `RFIDServer`).

```
 Coletores Android  ──HTTPS 8443 (chave do coletor + login do operador)──►  RFID Server  ──►  Banco H2 (arquivo)
 Navegador (equipe) ──HTTPS 8443 (login e senha)─────────────────────────►  (Spring Boot)      + backups diários
```

---

## Sumário

1. [Como funciona](#como-funciona)
2. [Telas web e permissões](#telas-web-e-permissões)
3. [API dos coletores](#api-dos-coletores)
4. [Segurança](#segurança)
5. [Banco de dados, migrações e backup](#banco-de-dados-migrações-e-backup)
6. [Instalação e primeira execução](#instalação-e-primeira-execução)
7. [Configuração (variáveis de ambiente)](#configuração-variáveis-de-ambiente)
8. [Desenvolvimento e testes](#desenvolvimento-e-testes)
9. [Logs](#logs)
10. [Problemas comuns](#problemas-comuns)

---

## Como funciona

### Conceitos

| Conceito | O que é |
|---|---|
| **Catálogo** | Tipos de equipamento (Notebook, Monitor…), marcas de cada tipo e modelos de cada marca. |
| **Etiqueta** | Uma etiqueta RFID (EPC) ligada a um equipamento: código interno, patrimônio, nº de série, tipo, marca e modelo. |
| **Pendente** | Etiqueta lida por um coletor que ainda não está cadastrada. Aparece em "Pendentes" para alguém cadastrar. |
| **Leitura** | Registro de que um coletor leu uma etiqueta cadastrada (data/hora, coletor, operador). Alimenta os relatórios. |
| **Inventário** | Conferência feita num **local**: o operador abre o inventário no coletor, lê as etiquetas e finaliza. |
| **Coletor** | Dispositivo Android identificado por uma **chave** configurada no servidor. |
| **Operador** | Usuário do sistema que fez login no coletor. Usa as permissões do próprio usuário. |

### Fluxo típico

1. O administrador monta o **catálogo** (tipos → marcas → modelos).
2. O coletor lê uma etiqueta desconhecida → ela vira **pendente**.
3. Alguém cadastra a etiqueta pela web (Pendentes → Cadastrar) ou pelo próprio coletor (se ele tiver permissão).
4. A partir daí, cada leitura dessa etiqueta vira um registro em **Leituras** e entra nos **Relatórios**,
   que se atualizam sozinhos em tempo real.
5. Periodicamente a equipe faz **inventários** por local; o resultado fica em **Inventários**.

> Uma etiqueta só aparece nas leituras e relatórios quando o cadastro está **completo** (código interno,
> tipo, marca, modelo, patrimônio e nº de série). Cadastros parciais são aceitos, mas ficam fora das leituras.

### Tecnologias

Kotlin 2.1 · Spring Boot 3.5 (Web MVC, Security, Data JPA, Thymeleaf) · H2 em arquivo · Flyway ·
Chart.js 4.4.1 (servido localmente, funciona sem internet) · BouncyCastle (certificado HTTPS) · Java 17.

---

## Telas web e permissões

Acesse `https://<servidor>:8443`. Na primeira vez, o sistema abre a **configuração inicial** para criar o
administrador; depois disso essa tela não aparece mais.

| Tela | Endereço | Permissão |
|---|---|---|
| Início (atalhos conforme as permissões) | `/dashboard` | logado |
| Etiquetas cadastradas (com busca) | `/admin/tags-cadastradas` | RFID · Ver |
| Pendentes | `/admin/tags-pendentes` | RFID · Ver |
| Cadastrar etiqueta | `/admin/cadastro` | RFID · Criar |
| Editar etiqueta (o EPC não muda) | `/admin/etiquetas/{id}/editar` | RFID · Editar |
| Excluir etiqueta (botão em Etiquetas cadastradas) | `/admin/etiquetas/{id}/excluir` | RFID · Excluir |
| Histórico das etiquetas (busca por EPC; acha também as excluídas) | `/admin/etiquetas/historico` | RFID · Ver |
| Leituras (paginada; filtros por dia, etiqueta, tipo, operador…) | `/admin/leituras-android` | Leituras · Ver |
| Inventários | `/admin/inventarios` | Leituras · Ver |
| Catálogo | `/admin/catalogo` | Catálogo · Ver (criar/excluir exigem Criar/Excluir) |
| Relatórios (gráficos, tempo real) | `/admin/dashboard` | Relatórios · Ver |
| Usuários (+ pedidos de nova senha e senha temporária) | `/admin/usuarios` | somente ADMIN |
| Coletores (último contato, IP, versão do app e operador de cada coletor) | `/admin/coletores` | somente ADMIN |
| App dos coletores (publicar versão nova do app) | `/admin/app` | somente ADMIN |
| Status do servidor (+ backup manual para ADMIN) | `/status` | logado |
| Meu perfil, trocar senha e email | `/perfil` | logado |
| Esqueceu a senha? (link na tela de login) | `/esqueci-senha` | público |

### Histórico das etiquetas

Todo cadastro, alteração e exclusão de etiqueta (pela web ou pelo coletor) fica registrado na mesma transação da
mudança: uma linha por campo alterado, com valor antigo e novo, quem fez (email na web, operador no coletor) e a
origem ("Web" ou "Coletor X"). Sem filtro, a tela mostra as últimas 200 mudanças; filtrando por EPC, mostra todas
daquela etiqueta, mesmo que ela já tenha sido excluída. Cada mudança também vai para o log, por exemplo:

```
Etiqueta E2806894000050123456ABCD (patrimônio PAT-200) alterada por admin@empresa.com: patrimonio
```

### Coletores

A tela **Coletores** (somente ADMIN) mostra cada coletor com chave em `RFID_CHAVES_DISPOSITIVOS`: situação (em uso
agora, sem uso no momento, sem contato há muito tempo, nunca conectou), último contato ("há 5 min"), IP, versão do
app (com destaque para quem está numa versão mais antiga que a mais nova vista) e último operador. O contato é
registrado a cada chamada aceita do coletor em `/api/android` (no máximo uma gravação por minuto por coletor) e a
versão vem dos headers `X-Versao-App` e `X-Versao-Codigo`, enviados pelo app a partir da 1.4.0. "Nunca conectou"
quer dizer que a chave existe no servidor, mas o coletor ainda não fez nenhuma chamada aceita: confira a chave e o
endereço nas Configurações do app.

### Exportar para Excel

As telas de **Leituras** e de **detalhe do inventário** têm os botões **Excel** e **CSV**
(`/admin/leituras-android/exportar` e `/admin/inventarios/{id}/exportar`, parâmetro `formato=xlsx|csv`), com a
mesma permissão da tela. Em Leituras, exporta **todas** as leituras do filtro aplicado (não só a página), até
100 mil; acima disso pede para filtrar mais. O log registra quem exportou, o filtro e quantas linhas.

- **Excel (.xlsx)**, o recomendado: textos ficam como texto (patrimônio `000123` não perde os zeros, EPC só com
  números não vira notação científica) e datas ficam como datas (dá para ordenar e filtrar).
- **CSV**: para importar em outros sistemas. Segue o padrão do Excel em português (`;` e UTF-8 com BOM), e texto
  que começa com `=`, `+`, `-` ou `@` ganha um `'` na frente, para não ser executado como fórmula.

### Erros do app nos coletores

O app guarda os travamentos e as falhas relevantes (envio recusado pelo servidor, erro do servidor na
sincronização, leitor RFID que não conecta, atualização que não baixou) e envia ao servidor quando há rede
(`POST /api/android/erros`, só a chave do coletor). Na tela **Coletores**, a coluna **Erros (24 h)** leva à lista de
erros do coletor, com a hora no coletor e a de chegada, o detalhe (stack trace), o número de vezes e filtro por
versão do app (para ver se o erro sumiu depois de uma atualização).

- Sem dados sensíveis: token da sessão, chave do coletor e senhas são mascarados no app e de novo no servidor.
- Contra app em loop de falha: o app agrupa os repetidos (número de vezes), o servidor aceita no máximo 50 por
  envio e `RFID_COLETORES_ERROS_POR_DIA` por coletor por dia (acima disso descarta, com um aviso por hora no log).
- Reenvio não duplica (o id é gerado no coletor). Erros com mais de `RFID_COLETORES_DIAS_ERROS` dias são apagados
  todo dia às 04:15.

### App dos coletores (atualização pelo servidor)

Na tela **App dos coletores** (somente ADMIN) o administrador envia o APK de produção, e os coletores oferecem a
atualização sozinhos na tela de login (ou em Configurações → "Procurar atualização do app"). O operador confirma a
instalação, como o Android exige, e o que está guardado no coletor continua depois da atualização.

Como isso distribui código para todos os coletores, o servidor só publica o APK que:

- tem a assinatura válida e é do pacote do app (conferido com a `apksig`, a mesma biblioteca do Android);
- está assinado com a **chave da empresa** (`RFID_APP_CERTIFICADO_SHA256`; sem ela a publicação fica desligada);
- tem **código de versão maior** que o de todas as já publicadas (o Android não instala versão menor por cima).

O arquivo é gravado como temporário e só vai para o nome final, gerado pelo servidor, depois de conferido. O
coletor confere o SHA-256 do APK baixado antes de instalar. Só as últimas versões (`RFID_APP_VERSOES_GUARDADAS`)
mantêm o arquivo. A pasta dos APKs **não entra no backup automático**: se o servidor for restaurado, publique o
APK de novo. A tela Coletores usa a versão publicada para marcar quem está desatualizado. Rotas do coletor (só a
chave, sem operador): `GET /api/android/atualizacao` e `GET /api/android/atualizacao/apk`.

### Inventário: o que faltou

O detalhe do inventário mostra o que **faltou** em relação ao **inventário finalizado anterior do mesmo local**
(o último finalizado antes do início deste; um inventário antigo compara com o que veio antes dele), com a data e
o operador da referência. Entram as etiquetas cadastradas encontradas na referência, com os dados do cadastro atual
(etiquetas excluídas depois ficam de fora). Inventário em andamento mostra "Faltando até agora" (lista
provisória); o primeiro inventário de um local diz que não há comparação. A exportação traz as lidas e, depois,
as que faltaram, com a situação `Faltando` (ou `Faltando até agora`).

### Perfis de usuário

| Perfil | Permissões |
|---|---|
| **Administrador** | Tudo, inclusive usuários, backup e console do banco. |
| **Gestor** | Ver, criar e editar em todos os módulos. Não exclui e não gerencia usuários. |
| **Usuário** | Só consulta (ver) em todos os módulos. |
| **Personalizado** | Os módulos e ações escolhidos no cadastro. |

Módulos: Catálogo, Etiquetas (RFID), Leituras, Relatórios, Usuários. Ações: Ver, Criar, Editar, Excluir.
O menu e os botões só mostram o que o usuário pode usar. Mudanças de permissão valem a partir do
próximo login (na web) e na próxima requisição (no coletor).

### Esqueceu a senha

O servidor não envia email: o pedido vai para o administrador dentro do sistema.

1. Na tela de login, a pessoa clica em **Esqueceu a senha?** e informa o email. A resposta é sempre a mesma,
   exista ou não o email (a tela não revela quem tem conta). Cada pessoa tem no máximo um pedido em aberto, e
   cada IP pode pedir até 5 vezes a cada 15 min.
2. O ADMIN vê um contador ao lado de **Usuários** no menu e, na tela de usuários, o quadro **Pedidos de nova
   senha**, com os botões *Gerar senha temporária* e *Descartar*. O botão *Nova senha* de cada linha gera uma
   senha temporária mesmo sem pedido (para quem pediu pessoalmente).
3. A senha temporária aparece **uma única vez**. O ADMIN entrega pessoalmente ou por um canal seguro. Gerar a
   senha também libera o login, se estava bloqueado por tentativas erradas.
4. Ao entrar com ela, a pessoa só acessa a tela de troca de senha até criar a senha nova.

No coletor, a tela de login também tem **Esqueceu a senha?** (`POST /api/android/esqueci-senha`), que cria o
mesmo pedido. Lá o limite de 5 pedidos a cada 15 min é por coletor e IP, para coletores atrás do mesmo IP não
travarem uns aos outros. O coletor **não aceita senha temporária**: o login responde 403 pedindo para criar a
senha nova na web primeiro.

Tema claro/escuro (segue o sistema operacional, com botão para trocar) e layout responsivo para celular.

---

## API dos coletores

Usada pelo app Android. Tudo em `/api/android/**`, sempre em HTTPS, JSON, sem sessão nem cookie.

### Autenticação em duas camadas

1. **Coletor** — header `X-Chave-Dispositivo: <chave>`. As chaves ficam em `RFID_CHAVES_DISPOSITIVOS`
   (`nome:chave`). Sem chave válida: **401**.
2. **Operador** — `POST /api/android/login` com email e senha de um usuário do sistema devolve um token de
   sessão (12 h). As demais chamadas enviam `X-Sessao-Operador: <token>`. A sessão vale só no coletor em
   que foi criada, e o operador usa as **permissões do próprio usuário** (Leituras para ler e inventariar,
   RFID · Criar para cadastrar).

### Trabalho offline

O app funciona sem rede durante a sessão e envia depois o que ficou na fila. Cada ação leva
`X-Momento` (data/hora em que aconteceu no coletor, ISO-8601 com fuso):

- uma ação feita **enquanto a sessão valia** é aceita mesmo que chegue depois do logout/expiração;
- mas só dentro do **prazo de sincronização** (`RFID_JANELA_SINCRONIZACAO`, padrão 72 h, pelo relógio do
  servidor). Depois disso o token morre de vez e ações antigas demais são recusadas — um token vazado não
  serve para sempre;
- reenviar a mesma ação não duplica nada (inventários têm id gerado no coletor; cadastro repetido igual
  responde sucesso).

### Rotas

| Método e rota | Para quê | Exige |
|---|---|---|
| `GET /api/android/ping` | Testa a conexão e a chave | chave |
| `POST /api/android/login` · `POST /api/android/logout` | Sessão do operador | chave |
| `POST /api/android/esqueci-senha` | Pede uma senha nova ao ADMIN (**202**, mesma resposta exista ou não o email) | chave |
| `GET /api/android/tags/{epc}` | Dados de uma etiqueta | operador · Leituras |
| `GET /api/android/tags/patrimonio/{patrimonio}` | EPC de um patrimônio (localizar equipamento) | operador · Leituras |
| `POST /api/android/leitura` | Registra a leitura de uma etiqueta | operador · Leituras |
| `POST /api/android/naocadastrada` | Envia etiqueta desconhecida para Pendentes | operador · Leituras |
| `POST /api/android/leituras/lote` | Até 500 leituras de um operador numa requisição: classifica e registra cada uma e devolve o resultado por item (`CADASTRADA` com os dados, `NAO_CADASTRADA`, `RECUSADA` ou `ERRO`). A hora de cada leitura é conferida contra a sessão; reenvio da mesma tag com a mesma hora não duplica | operador · Leituras |
| `GET /api/android/catalogo` | Catálogo para o formulário de cadastro | operador |
| `POST /api/android/tags` | Cadastra etiqueta pelo coletor | operador · RFID Criar **e** coletor em `RFID_COLETORES_CADASTRO` |
| `GET /api/android/locais` | Locais já usados em inventários | operador · Leituras |
| `POST /api/android/inventarios` | Abre inventário num local | operador · Leituras |
| `POST /api/android/inventarios/{id}/itens` | Envia etiquetas lidas | operador · Leituras |
| `POST /api/android/inventarios/{id}/finalizar` | Finaliza o inventário | operador · Leituras |
| `GET /api/android/inventarios/esperado?local=...` | O que é esperado no local, para o coletor mostrar o que falta (ver abaixo) | operador · Leituras |

**O que é esperado num local:** as etiquetas **cadastradas** encontradas no **último inventário finalizado** daquele
local (não há campo de local no cadastro da etiqueta). O nome do local segue a regra do inventário (ignora
maiúsculas e espaços extras). Etiquetas excluídas do cadastro depois ficam de fora, e os dados (patrimônio, tipo,
marca, modelo) são os do cadastro atual. Local sem inventário finalizado responde `"temReferencia": false` e a
lista vazia (primeiro inventário dele). A resposta traz também a data e o operador do inventário de referência.

Erros sempre em JSON: `{"status": 400, "mensagem": "...", "codigo": null, "caminho": "..."}`.
401 = chave ou sessão inválida · 403 = sem permissão · 409 = conflito (ex.: item em uso) ·
410 = rota antiga removida (atualize o app) · 426 = chamada por HTTP (use HTTPS) · 429 = login bloqueado por tentativas (header `Retry-After`).

> As rotas antigas dos coletores (`/api/tags/...` e `/api/rfid/...`) foram **removidas**: respondem
> **410** explicando que o app precisa ser atualizado, e o servidor registra no log (uma vez por hora por
> coletor) quem ainda as chama — útil para achar coletor com app antigo.

---

## Segurança

| Proteção | Como funciona |
|---|---|
| **HTTPS** | Porta 8443 com certificado próprio gerado na primeira execução (10 anos). A porta HTTP 8082 só redireciona as telas e recusa a API com 426. Os coletores fixam a **impressão digital** do certificado, mostrada em `/status`. |
| **Configuração inicial** | `/setup` só funciona com o banco vazio; depois ninguém cria outro administrador por ali. |
| **Senhas** | Mínimo 8 caracteres, letras e números, sem o nome/email, sem senhas comuns, até 72 bytes. Quem entra com senha antiga fora da regra é levado à troca de senha. Senhas guardadas com BCrypt. |
| **Tentativas de login** | 5 erros no mesmo email (na web e no coletor somados) ou 20 na mesma origem em 15 min bloqueiam por 15 min. A origem é o IP na web e o **coletor + IP** no coletor: vários coletores atrás do mesmo IP (NAT) não se bloqueiam por causa de um operador errando a senha. Durante o bloqueio nem a senha certa entra. Email inexistente recebe a mesma resposta que senha errada. |
| **Permissões** | Por módulo e ação, conferidas em cada rota (não só escondendo botões). |
| **CSRF** | Ativo em todas as telas; a API dos coletores não usa cookie, então não precisa. |
| **Sessões do coletor** | Token aleatório de 32 bytes; o banco guarda só o hash SHA-256. |
| **Integridade** | Chaves estrangeiras impedem excluir tipo/marca/modelo usado por etiquetas. |
| **Console do H2** | `/h2-console` só para ADMIN e só a partir da própria máquina. Se não usar, desligue com `RFID_H2_CONSOLE=false`. |

---

## Banco de dados, migrações e backup

- **H2 em arquivo** em `./data/rfid-db.mv.db` (mude com `RFID_DB_PATH`). Cada gravação vai para o disco na hora.
- **Flyway** cria e evolui as tabelas; o Hibernate só confere (`ddl-auto: validate`). Toda mudança de tabela é
  uma nova migração:

  | Versão | Arquivo | Conteúdo |
  |---|---|---|
  | V1 | `db/migration/V1__schema_inicial.sql` | Catálogo, etiquetas, pendentes, leituras, usuários e permissões |
  | V2 | `kotlin/db/migration/V2__Chaves_estrangeiras_tags.kt` | Liga etiquetas ao catálogo (limpa referências quebradas e registra no log) |
  | V3 | `db/migration/V3__inventarios.sql` | Locais, inventários e itens |
  | V4 | `db/migration/V4__sessoes_operador.sql` | Sessões de operador e coluna "operador" nos registros |
  | V5 | `db/migration/V5__recuperacao_senha.sql` | Pedido de nova senha e senha temporária nos usuários |
  | V6 | `db/migration/V6__coletores.sql` | Situação de cada coletor (tela Coletores) |
  | V7 | `db/migration/V7__versoes_app.sql` | Versões do app publicadas pelo servidor |
  | V8 | `db/migration/V8__erros_coletor.sql` | Erros do app enviados pelos coletores |
  | V9 | `db/migration/V9__historico_etiquetas.sql` | Histórico de cadastro, alteração e exclusão das etiquetas |

- **Backup automático** todo dia às 02:00 em `./data/backups` (zip consistente, sem parar o servidor, com o certificado HTTPS junto: `rfid-https.p12` e `.senha`), mantendo
  os 14 mais recentes. Ao subir, se não houver backup das últimas 24 h, faz um na hora. O ADMIN também pode
  fazer pelo botão em `/status`.

  **Restaurar:** pare o servidor, extraia o `.zip` na pasta do banco (`./data`, substituindo os arquivos) e suba de
  novo. Isso restaura o banco **e** o certificado: os coletores continuam reconhecendo o servidor sem novo
  pareamento. Se o certificado fica em outro caminho (`RFID_HTTPS_KEYSTORE`), copie o `.p12` e o `.senha` para lá.

  > Recomendado: aponte `RFID_BACKUP_PASTA` para **outro disco ou pasta de rede**. Como o backup contém a
  > chave privada do certificado e a senha dela, essa pasta deve ser acessível só a quem administra o servidor.
  > Senha informada em `RFID_HTTPS_SENHA` não vai para o backup: guarde-a à parte.

---

## Instalação e primeira execução

Requisitos: **Java 17** (para rodar) e o Gradle wrapper do projeto (para compilar).

```bash
./gradlew bootJar                      # gera build/libs/rfid-server-<versão>.jar
java -jar build/libs/rfid-server-1.3.1.jar
```

Na primeira execução o servidor:

1. cria o banco em `./data` e aplica as migrações;
2. gera o certificado HTTPS em `./data/rfid-https.p12` (veja a impressão digital no log ou em `/status`);
3. faz o primeiro backup.

Depois:

1. Abra `https://<servidor>:8443` (o navegador avisa do certificado próprio: aceite a exceção uma vez).
2. Crie o administrador na tela de configuração inicial.
3. Cadastre o catálogo e os usuários.
4. Configure os coletores (ver abaixo) e reinicie o servidor.

### Ligar os coletores

1. Escolha um nome e uma chave longa (16+ caracteres) para cada coletor e configure
   `RFID_CHAVES_DISPOSITIVOS=coletor-01:chave-longa-1,coletor-02:chave-longa-2`.
2. Se algum coletor pode cadastrar etiquetas: `RFID_COLETORES_CADASTRO=coletor-01`.
3. No app de cada coletor, em **Configurações**, informe o **IP** do servidor (o app usa sempre
   `https://IP:8443`) e a chave, e toque em **Salvar**.
4. **Cadastre o certificado no coletor:** o app mostra a impressão digital do certificado do servidor para ser
   confirmada (ver abaixo).
5. Confira o fuso do Android do coletor (ex.: America/Sao_Paulo, data e hora automáticas): o app mostra os
   horários no fuso do aparelho, e um coletor em GMT mostra 3 horas a mais.

> Execute o servidor sempre a partir da mesma pasta (o banco e o certificado ficam em `./data`, relativo a ela),
> ou defina `RFID_DB_PATH` e `RFID_HTTPS_KEYSTORE` com caminhos absolutos.

### Cadastrar o certificado nos coletores

O certificado HTTPS é próprio do servidor (gerado na primeira execução, válido por 10 anos), e cada coletor só
conversa com o servidor depois que alguém confirma, uma vez por coletor, que o certificado é o deste servidor.
Assim, outro computador na rede não consegue se passar pelo servidor para receber leituras, cadastros e senhas
dos operadores.

**1. Pegue a impressão digital SHA-256 no servidor** (32 pares, ex.: `63:0F:85:…:92:36`):

- na tela **Status** (`/status`, logado), no campo da impressão digital do certificado HTTPS; ou
- no **log**, que mostra a digital a cada inicialização. Na pasta de onde o servidor roda:
  ```powershell
  Select-String "SHA-256 do certificado HTTPS" .\data\logs\rfid-server.log | Select-Object -Last 1
  ```

**2. Confirme no coletor:** ao salvar o IP e a chave, o app abre **"Confirmar o servidor"** com a digital em
4 linhas de 8 pares. Compare **todos** os pares com a do passo 1 e toque em **"É igual, confiar"** só se forem
iguais; com qualquer diferença, **"Não confiar"**. Confirmado, a tela mostra *"Servidor: conectado com segurança
(HTTPS) ✅"* e o coletor passa a aceitar só esse certificado.

**Trocar o certificado** (servidor novo, `.p12` perdido, backup de outro servidor): todo coletor passa a mostrar
**"Certificado do servidor mudou"**, com a digital nova e a anterior, e recusa o servidor até ser confirmado de novo
como no passo 2. Para evitar isso, mantenha `data/rfid-https.p12` e `data/rfid-https.p12.senha` no backup (o
backup automático já inclui os dois) e restaure os dois juntos. Se o alerta aparecer sem que o certificado tenha sido
trocado, **não confie**: pode ser outro computador se passando pelo servidor.

### Rodar no Windows fora do IntelliJ

Pelo IntelliJ o servidor para quando o IntelliJ fecha. Para rodar sozinho, monte uma pasta do servidor
(ex.: `C:\RFID_Servidor`) com:

```
RFID_Servidor\
├── iniciar-servidor.bat          ← copie de scripts\windows\
├── servidor\rfid-server.jar      ← o build/libs/rfid-server-<versão>.jar (./gradlew bootJar), renomeado
├── config\application.properties ← copie de scripts\windows\application.properties.exemplo e preencha
└── data\                         ← criada na primeira execução (banco, certificado, logs, backups)
```

1. Em `config\application.properties`, coloque o nome e a chave de cada coletor (`rfid.dispositivos.chaves`) e
   os que podem cadastrar (`rfid.dispositivos.cadastro`). As outras opções vêm comentadas com o valor padrão.
   Esse arquivo faz o papel das variáveis de ambiente da tabela abaixo e **não vai para o git** (tem as chaves).
2. No `iniciar-servidor.bat`, confira o caminho do Java (`JAVA`). Use o mesmo `java.exe` liberado no Firewall do
   Windows; com outro, libere a entrada na porta 8443 para ele, senão os coletores não conectam.
3. Dê dois cliques no `.bat`. A janela fica aberta com o servidor no ar; para parar, **Ctrl+C** (o servidor fecha o
   banco antes de sair). O `.bat` não inicia se a porta 8443 já estiver em uso (ex.: o servidor do IntelliJ ainda
   rodando), para os dois não disputarem a porta e o banco.

**Versão nova do servidor:** pare com Ctrl+C, troque `servidor\rfid-server.jar` e inicie de novo. Os dados e o
certificado ficam em `data\` e não mudam.

**Serviço do Windows (iniciar sozinho ao ligar o computador):** registre o `.bat` com o parâmetro `/servico` (não
espera tecla ao terminar) numa ferramenta como NSSM ou WinSW, com a pasta do servidor como pasta de trabalho.
Confira na ferramenta que ela para o serviço com Ctrl+C (a NSSM faz isso por padrão), para o banco ser fechado.

> Já tinha um banco do servidor rodando pelo IntelliJ? Ele fica em `data\` dentro da pasta de trabalho da
> configuração de execução do IntelliJ.
> Monte a pasta do servidor nesse mesmo lugar, ou copie a `data\` inteira (com o servidor parado) para a pasta
> nova: assim o banco e o certificado são os mesmos e os coletores não precisam ser configurados de novo.

---

## Configuração (variáveis de ambiente)

Todas têm padrão no `application.yml`; normalmente só as de coletores precisam ser definidas.

| Variável | Padrão | Para quê |
|---|---|---|
| `RFID_CHAVES_DISPOSITIVOS` | *(vazio = nenhum coletor entra)* | `nome:chave` dos coletores, separados por vírgula |
| `RFID_COLETORES_CADASTRO` | *(vazio)* | Coletores que podem cadastrar etiquetas |
| `RFID_COLETORES_DIAS_SEM_CONTATO` | `7` | Dias sem contato até o coletor aparecer destacado na tela Coletores |
| `RFID_APP_CERTIFICADO_SHA256` | *(o da chave da empresa)* | Impressão digital do certificado da chave do app: só APK assinado com ela é publicado (vazio = publicação desligada) |
| `RFID_APP_PASTA` | `./data/app` | Pasta dos APKs publicados (fora do backup automático) |
| `RFID_APP_VERSOES_GUARDADAS` | `3` | Quantas versões mantêm o arquivo no disco |
| `RFID_COLETORES_ERROS_POR_DIA` | `500` | Erros do app aceitos por coletor por dia (acima disso são descartados) |
| `RFID_COLETORES_DIAS_ERROS` | `90` | Por quantos dias os erros dos coletores ficam guardados |
| `RFID_DB_PATH` | `./data/rfid-db` | Arquivo do banco |
| `RFID_DB_USER` / `RFID_DB_PASSWORD` | `sa` / *(vazio)* | Usuário do banco |
| `RFID_HTTPS_HABILITADO` | `true` | Liga o HTTPS |
| `RFID_HTTPS_PORTA` | `8443` | Porta HTTPS (a HTTP é 8082) |
| `RFID_HTTPS_KEYSTORE` | `./data/rfid-https.p12` | Certificado (use o da empresa, se houver) |
| `RFID_HTTPS_SENHA` | *(gerada e salva em `.p12.senha`)* | Senha do certificado |
| `RFID_HTTPS_NOMES` | *(vazio)* | Nomes/IPs extras do certificado gerado (só na geração) |
| `RFID_DURACAO_SESSAO_OPERADOR` | `12h` | Duração do login no coletor |
| `RFID_JANELA_SINCRONIZACAO` | `72h` | Tempo máximo que um coletor pode ficar offline sem perder ações |
| `RFID_LEITURAS_POR_PAGINA` | `50` | Leituras por página na tela de leituras (10 a 200) |
| `RFID_LEITURAS_RETER_DIAS` | `0` (desligada) | Apaga leituras com mais de N dias, todo dia às 03:30 (mínimo 30; não afeta inventários) |
| `RFID_SENHA_TAMANHO_MINIMO` | `8` | Tamanho mínimo de senha |
| `RFID_LOGIN_MAX_TENTATIVAS` | `5` | Erros por email até bloquear |
| `RFID_LOGIN_MAX_TENTATIVAS_IP` | `20` | Erros por origem até bloquear (IP na web, coletor + IP no coletor) |
| `RFID_LOGIN_BLOQUEIO_MINUTOS` | `15` | Tempo de bloqueio |
| `RFID_BACKUP_HABILITADO` | `true` | Backup automático |
| `RFID_BACKUP_PASTA` | `./data/backups` | Pasta dos backups |
| `RFID_BACKUP_CRON` | `0 0 2 * * *` | Horário (todo dia às 02:00) |
| `RFID_BACKUP_MANTER` | `14` | Quantos backups guardar |
| `RFID_LOG_ARQUIVO` | `./data/logs/rfid-server.log` | Arquivo de log (vazio = só console); um por dia, guarda 30 dias |
| `RFID_MOSTRAR_SQL` | `false` | Mostra no log cada SQL executado (só para investigar problema) |
| `RFID_H2_CONSOLE` | `true` | Liga o console do H2 em `/h2-console` |

---

## Desenvolvimento e testes

```bash
./gradlew test        # ~1 min; relatório em build/reports/tests/test/index.html
./gradlew bootRun     # sobe o servidor
```

Se aparecer `JAVA_HOME is not set`, aponte para um JDK 17.

**CI:** a cada push (qualquer branch) e em pull requests, o GitHub Actions roda `./gradlew test`
(`.github/workflows/testes.yml`). O resultado aparece na aba **Actions** do repositório; se algum teste falhar,
o relatório HTML fica para download na própria execução (Artifacts).

Estrutura (pacote `org.example`):

```
src/main/kotlin/
  config/        segurança, filtros (chave do coletor, operador, bloqueio de login), HTTPS, erros, banco
  controller/    telas web e APIs (Android*, Inventario*, Cadastro*, Operador*)
  service/       regras de negócio (etiquetas, catálogo, usuários, senha, backup, inventário, sessões…)
  model/ repository/ request/ response/ dto/ security/
  db/migration/  migrações escritas em Kotlin (V2)
src/main/resources/
  db/migration/  migrações SQL
  templates/     telas (Thymeleaf), layout em templates/layout
  static/        css/app.css, js/app.js, js/chart.umd.min.js, img/icones.svg
src/test/kotlin/
  service/ config/ repository/ migracao/   testes unitários
  integracao/                              testes com a aplicação completa (MockMvc + H2 em memória)
  suporte/                                 base dos testes de integração e usuários simulados
```

Convenções:

- **Logs em português** e nunca com senha, token ou chave.
- Toda rota em `/admin/**` precisa de `@PreAuthorize` (a cadeia de segurança só exige login ali).
- Erros de validação: lance `IllegalArgumentException` (vira 400 com a mensagem); conflito de estado:
  `IllegalStateException` (409). O tratamento global devolve JSON nas APIs e a página de erro nas telas.
- Testes de integração estendem `suporte/IntegracaoBase` (perfil `test`, banco em memória, transação desfeita
  a cada teste); use senhas que passam na política (ex.: `Kx9mPq2v`) e email/IP únicos em testes de login.

---

## Logs

Tudo em português. Sai no console e no arquivo `./data/logs/rfid-server.log` (mude com `RFID_LOG_ARQUIVO`):
um arquivo por dia (ou a cada 10 MB), guardando os últimos 30 dias. Exemplos do que aparece:

```
Servidor RFID versão 1.3.1 no ar na porta 8443 (iniciado em 13/10/2026 11:17, pronto em 9,8 s)
Banco de dados: H2 em arquivo: C:\rfid\data\rfid-db.mv.db (1532 KB)
Backup concluído: rfid-db-2026-10-13_020000.zip (412 KB em 180 ms)
Login de 'ana@empresa.com' bloqueado por 15 min após 5 tentativas com senha errada (última origem: coletor-01@10.0.0.5)
Exclusão recusada: modelo 'Latitude 5440' (id 1) está em uso por 7 etiqueta(s) (tentativa de ana@empresa.com)
Erro inesperado [código cde75674] em POST /api/catalogo/marca (usuário ana@empresa.com)
```

Quando um usuário relatar um erro com **código** (ex.: `cde75674`), procure esse código no log: a linha traz o
detalhe técnico completo.

---

## Problemas comuns

| Sintoma | Causa provável / o que fazer |
|---|---|
| Coletor recebe 401 em tudo | Chave não configurada ou diferente da do app (`RFID_CHAVES_DISPOSITIVOS`, reiniciar o servidor). |
| Coletor recebe 426 | O app está usando `http://…:8082`; troque para `https://…:8443`. |
| Coletor recebe 410 / log "Rota antiga dos coletores chamada" | O coletor está com um app antigo (rotas /api/tags ou /api/rfid): atualize o app. O log mostra o IP do coletor. |
| Coletor diz que o certificado não confere | A impressão digital no app difere da de `/status` (certificado foi trocado ou restaurado de outro servidor). Confirme de novo em cada coletor, conferindo a digital nova: ver [Cadastrar o certificado nos coletores](#cadastrar-o-certificado-nos-coletores). |
| "Muitas tentativas sem sucesso" | Bloqueio de login: espere o tempo indicado (ou reinicie o servidor, que zera os contadores). |
| Etiqueta lida não aparece nas leituras | Cadastro incompleto (falta tipo, marca, modelo, patrimônio ou nº de série). |
| Servidor não sobe: certificado não abre | O `.p12` ou o `.senha` foi alterado/perdido. Restaure os dois do backup — não apague, senão um novo certificado é gerado e os coletores precisam ser pareados de novo. |
| Não consigo excluir um modelo/marca/tipo | Ele está em uso por etiquetas (a mensagem diz quantas). |
| Dados sumiram depois de mover o servidor de pasta | O banco é relativo à pasta de execução: use a pasta original ou defina `RFID_DB_PATH`. |
