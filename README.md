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

Kotlin 2.1 · Spring Boot 3.2 (Web MVC, Security, Data JPA, Thymeleaf) · H2 em arquivo · Flyway ·
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
| Leituras (filtros por dia, etiqueta, tipo…) | `/admin/leituras-android` | Leituras · Ver |
| Inventários | `/admin/inventarios` | Leituras · Ver |
| Catálogo | `/admin/catalogo` | Catálogo · Ver (criar/excluir exigem Criar/Excluir) |
| Relatórios (gráficos, tempo real) | `/admin/dashboard` | Relatórios · Ver |
| Usuários | `/admin/usuarios` | somente ADMIN |
| Status do servidor (+ backup manual para ADMIN) | `/status` | logado |
| Meu perfil, trocar senha e email | `/perfil` | logado |

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
| `GET /api/android/tags/{epc}` | Dados de uma etiqueta | operador · Leituras |
| `GET /api/android/tags/patrimonio/{patrimonio}` | EPC de um patrimônio (localizar equipamento) | operador · Leituras |
| `POST /api/android/leitura` | Registra a leitura de uma etiqueta | operador · Leituras |
| `POST /api/android/naocadastrada` | Envia etiqueta desconhecida para Pendentes | operador · Leituras |
| `GET /api/android/catalogo` | Catálogo para o formulário de cadastro | operador |
| `POST /api/android/tags` | Cadastra etiqueta pelo coletor | operador · RFID Criar **e** coletor em `RFID_COLETORES_CADASTRO` |
| `GET /api/android/locais` | Locais já usados em inventários | operador · Leituras |
| `POST /api/android/inventarios` | Abre inventário num local | operador · Leituras |
| `POST /api/android/inventarios/{id}/itens` | Envia etiquetas lidas | operador · Leituras |
| `POST /api/android/inventarios/{id}/finalizar` | Finaliza o inventário | operador · Leituras |

Erros sempre em JSON: `{"status": 400, "mensagem": "...", "codigo": null, "caminho": "..."}`.
401 = chave ou sessão inválida · 403 = sem permissão · 409 = conflito (ex.: item em uso) ·
426 = chamada por HTTP (use HTTPS) · 429 = login bloqueado por tentativas (header `Retry-After`).

> Rotas antigas (`/api/tags/*`, `/api/rfid/*`) ainda existem para compatibilidade, exigem login web e
> devem ser removidas quando todos os coletores usarem `/api/android`.

---

## Segurança

| Proteção | Como funciona |
|---|---|
| **HTTPS** | Porta 8443 com certificado próprio gerado na primeira execução (10 anos). A porta HTTP 8082 só redireciona as telas e recusa a API com 426. Os coletores fixam a **impressão digital** do certificado, mostrada em `/status`. |
| **Configuração inicial** | `/setup` só funciona com o banco vazio; depois ninguém cria outro administrador por ali. |
| **Senhas** | Mínimo 8 caracteres, letras e números, sem o nome/email, sem senhas comuns, até 72 bytes. Quem entra com senha antiga fora da regra é levado à troca de senha. Senhas guardadas com BCrypt. |
| **Tentativas de login** | 5 erros no mesmo email ou 20 no mesmo IP em 15 min bloqueiam por 15 min — na web e no coletor. Durante o bloqueio nem a senha certa entra. Email inexistente recebe a mesma resposta que senha errada. |
| **Permissões** | Por módulo e ação, conferidas em cada rota (não só escondendo botões). |
| **CSRF** | Ativo em todas as telas; a API dos coletores não usa cookie, então não precisa. |
| **Sessões do coletor** | Token aleatório de 32 bytes; o banco guarda só o hash SHA-256. |
| **Integridade** | Chaves estrangeiras impedem excluir tipo/marca/modelo usado por etiquetas. |
| **Console do H2** | `/h2-console` só para ADMIN e só a partir da própria máquina. Desative em produção (`spring.h2.console.enabled=false`) se não usar. |

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

- **Backup automático** todo dia às 02:00 em `./data/backups` (zip consistente, sem parar o servidor), mantendo
  os 14 mais recentes. Ao subir, se não houver backup das últimas 24 h, faz um na hora. O ADMIN também pode
  fazer pelo botão em `/status`.

  **Restaurar:** pare o servidor, extraia o `.zip` na pasta do banco (substituindo `rfid-db.mv.db`) e suba de novo.

  > Recomendado: aponte `RFID_BACKUP_PASTA` para **outro disco ou pasta de rede**, e guarde também
  > `data/rfid-https.p12` e `data/rfid-https.p12.senha` (o backup automático copia só o banco). Sem o
  > certificado, todos os coletores precisam ser pareados de novo.

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
3. No app de cada coletor, configure o endereço `https://<servidor>:8443`, a chave e a **impressão digital**
   do certificado (copie de `/status`).

> Execute o servidor sempre a partir da mesma pasta (o banco e o certificado ficam em `./data`, relativo a ela),
> ou defina `RFID_DB_PATH` e `RFID_HTTPS_KEYSTORE` com caminhos absolutos.

---

## Configuração (variáveis de ambiente)

Todas têm padrão no `application.yml`; normalmente só as de coletores precisam ser definidas.

| Variável | Padrão | Para quê |
|---|---|---|
| `RFID_CHAVES_DISPOSITIVOS` | *(vazio = nenhum coletor entra)* | `nome:chave` dos coletores, separados por vírgula |
| `RFID_COLETORES_CADASTRO` | *(vazio)* | Coletores que podem cadastrar etiquetas |
| `RFID_DB_PATH` | `./data/rfid-db` | Arquivo do banco |
| `RFID_DB_USER` / `RFID_DB_PASSWORD` | `sa` / *(vazio)* | Usuário do banco |
| `RFID_HTTPS_HABILITADO` | `true` | Liga o HTTPS |
| `RFID_HTTPS_PORTA` | `8443` | Porta HTTPS (a HTTP é 8082) |
| `RFID_HTTPS_KEYSTORE` | `./data/rfid-https.p12` | Certificado (use o da empresa, se houver) |
| `RFID_HTTPS_SENHA` | *(gerada e salva em `.p12.senha`)* | Senha do certificado |
| `RFID_HTTPS_NOMES` | *(vazio)* | Nomes/IPs extras do certificado gerado (só na geração) |
| `RFID_DURACAO_SESSAO_OPERADOR` | `12h` | Duração do login no coletor |
| `RFID_JANELA_SINCRONIZACAO` | `72h` | Tempo máximo que um coletor pode ficar offline sem perder ações |
| `RFID_SENHA_TAMANHO_MINIMO` | `8` | Tamanho mínimo de senha |
| `RFID_LOGIN_MAX_TENTATIVAS` | `5` | Erros por email até bloquear |
| `RFID_LOGIN_MAX_TENTATIVAS_IP` | `20` | Erros por IP até bloquear |
| `RFID_LOGIN_BLOQUEIO_MINUTOS` | `15` | Tempo de bloqueio |
| `RFID_BACKUP_HABILITADO` | `true` | Backup automático |
| `RFID_BACKUP_PASTA` | `./data/backups` | Pasta dos backups |
| `RFID_BACKUP_CRON` | `0 0 2 * * *` | Horário (todo dia às 02:00) |
| `RFID_BACKUP_MANTER` | `14` | Quantos backups guardar |

---

## Desenvolvimento e testes

```bash
./gradlew test        # ~1 min; relatório em build/reports/tests/test/index.html
./gradlew bootRun     # sobe o servidor
```

Se aparecer `JAVA_HOME is not set`, aponte para um JDK 17.

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

Tudo em português. Exemplos do que aparece:

```
Servidor RFID versão 1.3.1 no ar na porta 8443 (iniciado em 13/10/2026 11:17, pronto em 9,8 s)
Banco de dados: H2 em arquivo: C:\rfid\data\rfid-db.mv.db (1532 KB)
Backup concluído: rfid-db-2026-10-13_020000.zip (412 KB em 180 ms)
Login de 'ana@empresa.com' bloqueado por 15 min após 5 tentativas com senha errada (último IP: 10.0.0.5)
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
| Coletor diz que o certificado não confere | A impressão digital no app difere da de `/status` (certificado foi trocado ou restaurado de outro servidor). |
| "Muitas tentativas sem sucesso" | Bloqueio de login: espere o tempo indicado (ou reinicie o servidor, que zera os contadores). |
| Etiqueta lida não aparece nas leituras | Cadastro incompleto (falta tipo, marca, modelo, patrimônio ou nº de série). |
| Servidor não sobe: certificado não abre | O `.p12` ou o `.senha` foi alterado/perdido. Restaure os dois do backup — não apague, senão um novo certificado é gerado e os coletores precisam ser pareados de novo. |
| Não consigo excluir um modelo/marca/tipo | Ele está em uso por etiquetas (a mensagem diz quantas). |
| Dados sumiram depois de mover o servidor de pasta | O banco é relativo à pasta de execução: use a pasta original ou defina `RFID_DB_PATH`. |
