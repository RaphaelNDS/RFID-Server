# RFID Server

Servidor do sistema de controle de equipamentos por etiquetas RFID. Ele guarda o cadastro das etiquetas
(qual equipamento cada etiqueta identifica), recebe as leituras e os inventários feitos pelos coletores
Android e oferece as telas web de administração e relatórios.

O app dos coletores fica em outro repositório: **RFID-SERVER-ANDROID** (pasta `RFIDServer`).

**Manuais:** [como instalar o servidor e conectar os coletores](docs/manual-conexao.md) ·
[principais erros e como resolver](docs/erros-comuns.md) ·
[levar o servidor para outra máquina](docs/migracao-servidor.md). O jar do servidor fica nas
[Releases do servidor](https://github.com/RaphaelNDS/RFID-Server/releases) e o APK nas
[Releases do app](https://github.com/RaphaelNDS/RFID-SERVER-ANDROID/releases).

```
 Coletores Android  ──HTTPS 8443 (chave do coletor + login do operador)──►  RFID Server  ──►  PostgreSQL (Docker)
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

Kotlin 2.1 · Spring Boot 3.5 (Web MVC, Security, Data JPA, Thymeleaf) · PostgreSQL 16 (Docker) · Flyway ·
Chart.js 4.4.1 (servido localmente, funciona sem internet) · BouncyCastle (certificado HTTPS) · Java 17.

---

## Telas web e permissões

Acesse `https://<servidor>:8443`. Na primeira vez, o sistema abre a **configuração inicial** para criar o
administrador; depois disso essa tela não aparece mais.

| Tela | Endereço | Permissão |
|---|---|---|
| Início (atalhos conforme as permissões) | `/dashboard` | logado |
| Etiquetas cadastradas (50 por página, busca no servidor; filtros de situação, local e responsável; exportar Excel/CSV) | `/admin/tags-cadastradas` | RFID · Ver |
| Importar planilha de etiquetas (xlsx ou csv; simula antes de gravar) | `/admin/tags-cadastradas/importar` | RFID · Criar (atualizar as existentes exige também Editar) |
| Etiquetas não vistas há N dias (sem leitura nem inventário; exportar) | `/admin/etiquetas/nao-vistas` | RFID · Ver |
| Pendentes (50 por página, busca pelo EPC) | `/admin/tags-pendentes` | RFID · Ver |
| Cadastrar etiqueta | `/admin/cadastro` | RFID · Criar |
| Editar etiqueta (o EPC não muda; dar baixa e desfazer a baixa) | `/admin/etiquetas/{id}/editar` | RFID · Editar |
| Excluir etiqueta (botão em Etiquetas cadastradas) | `/admin/etiquetas/{id}/excluir` | RFID · Excluir |
| Histórico das etiquetas (busca por EPC; acha também as excluídas) | `/admin/etiquetas/historico` | RFID · Ver |
| Leituras (paginada; filtros por dia, etiqueta, tipo, operador…; conta até 10.000 e acima disso mostra "mais de") | `/admin/leituras-android` | Leituras · Ver |
| Inventários (50 por página) | `/admin/inventarios` | Leituras · Ver |
| Catálogo | `/admin/catalogo` | Catálogo · Ver (criar/excluir exigem Criar/Excluir) |
| Locais (unidades e locais; desativar sem apagar) | `/admin/locais` | Catálogo · Ver (criar/alterar exigem Criar/Editar) |
| Transferências (guias numeradas; busca por etiqueta ou patrimônio) e a guia para imprimir | `/admin/transferencias` · `/admin/transferencias/{id}` | RFID · Ver |
| Nova transferência | `/admin/transferencias/nova` | RFID · Editar |
| Ação sobre os bens que faltaram (no detalhe do inventário finalizado) | `/admin/inventarios/{id}/divergencias` | RFID · Editar |
| PDFs (1.8.0): laudo do inventário · guia | `/admin/inventarios/{id}/laudo.pdf` · `/admin/transferencias/{id}/guia.pdf` | as mesmas da tela de onde saem (Leituras · Ver; RFID · Ver) |
| Manutenções (1.11.0): lista (abertas, atrasadas, encerradas) · nova · a ordem (concluir ou cancelar) · ordem em PDF | `/admin/manutencoes` · `/admin/manutencoes/nova` · `/admin/manutencoes/{id}` · `/admin/manutencoes/{id}/ordem.pdf` | Manutenção · Ver (abrir: Criar; concluir e cancelar: Editar) |
| Pedidos de locação (1.12.0): lista · novo · o pedido (linhas, saída, devolução, conferência) · devoluções a conferir · contrato em PDF | `/admin/pedidos` · `/admin/pedidos/novo` · `/admin/pedidos/{id}` · `/admin/pedidos/devolucoes` · `/admin/pedidos/{id}/contrato.pdf` | Locação · Ver (criar pedido e linha: Criar; o resto: Editar) |
| Disponibilidade por tipo e modelo (1.13.0); o painel do dia fica na tela Início (`/dashboard`) | `/admin/disponibilidade` | Locação · Ver |
| Clientes (1.12.0) | `/admin/clientes` | Locação · Ver (cadastrar: Criar; alterar: Editar) |
| Venda do equipamento (1.12.0), na edição da etiqueta | `/admin/etiquetas/{id}/venda` · `/admin/etiquetas/{id}/venda/desfazer` | RFID · Editar |
| Fotos e anexos do bem (1.9.0), na edição da etiqueta: enviar e excluir · abrir ou baixar | `/admin/etiquetas/{id}/anexos` · `/admin/etiquetas/{id}/anexos/{anexo}` | RFID · Editar · RFID · Ver |
| Relatórios (gráficos; avisa quando chegam leituras novas; período de até 366 dias) | `/admin/dashboard` | Relatórios · Ver |
| Usuários (+ pedidos de nova senha e senha temporária; editar perfil e permissões; desativar e reativar) | `/admin/usuarios` | somente ADMIN |
| Coletores (último contato, IP, versão do app, operador, fila de envio e estado do aparelho de cada coletor) | `/admin/coletores` | somente ADMIN |
| App dos coletores (publicar versão nova do app; retirar a publicada com problema) | `/admin/app` | somente ADMIN |
| Auditoria (quem fez o quê em usuários, senhas, catálogo, app, exportações e backup) | `/admin/auditoria` | somente ADMIN |
| Monitoramento (`{"status":"UP"}`, sem login e sem detalhes) | `/actuator/health` | público |
| Status do servidor (impressão digital do certificado, backups e backup manual) | `/status` | somente ADMIN |
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

### Auditoria

Ações de usuários e de configuração ficam numa tabela própria (V13), que não some depois de 30 dias como o log:
criação, edição, desativação e reativação de usuários; senha temporária gerada, pedido de senha descartado, troca
de senha e de email pelo próprio usuário; PIN do coletor liberado; cadastro, edição e exclusão no catálogo;
publicação e retirada de versão do app; exportações (leituras, inventário, etiquetas, não vistas) e backup manual.
Cada registro tem quando, quem (email na web, operador no coletor), por onde ("Web 10.0.0.5" ou "Coletor X"),
área, ação, sobre quem/o quê e detalhe. A tela (somente ADMIN) filtra por área e busca em todos os campos.
As mudanças nas etiquetas continuam no histórico próprio delas (abaixo).

### Etiquetas não vistas

`/admin/etiquetas/nao-vistas?dias=30` lista as etiquetas cadastradas sem nenhuma leitura nem inventário há mais
de N dias (as nunca vistas primeiro), com a última vez vista e o **último local** (o do inventário mais recente em
que a etiqueta foi lida). É o relatório de equipamento sumido, emprestado ou com a etiqueta danificada. O
**Excel/CSV do cadastro** (botões na tela de etiquetas, respeitando a busca) traz as mesmas colunas: última
leitura, último inventário, último local e dias sem ser vista.

### Coletores

A tela **Coletores** (somente ADMIN) mostra cada coletor com chave em `RFID_CHAVES_DISPOSITIVOS`: situação (em uso
agora, sem uso no momento, sem contato há muito tempo, nunca conectou), último contato ("há 5 min"), IP, versão do
app (com destaque para quem está numa versão mais antiga que a mais nova vista) e último operador. O contato é
registrado a cada chamada aceita do coletor em `/api/android` (no máximo uma gravação por minuto por coletor) e a
versão vem dos headers `X-Versao-App` e `X-Versao-Codigo`, enviados pelo app a partir da 1.4.0. "Nunca conectou"
quer dizer que a chave existe no servidor, mas o coletor ainda não fez nenhuma chamada aceita: confira a chave e o
endereço nas Configurações do app.

A partir do app 1.7, a tela mostra também o estado do aparelho, pelo último envio dele:
- **Fila de envio:** quantos registros esperam envio e quando foi a última sincronização completa;
- **Aparelho:** modelo e versão do Android, a diferença do relógio do coletor para o do servidor e o espaço livre.

O quadro **Precisam de atenção** conta os coletores com leitura na fila há mais de 1 h, relógio com mais de 2 min de
diferença (o mesmo limite do aviso no app) ou menos de 200 MB livres. Esses coletores também aparecem destacados.
Os dados vêm dos headers `X-Fila-Pendentes`, `X-Fila-Mais-Antiga`, `X-Ultima-Sinc-Ok`, `X-Espaco-Livre` (em MB),
`X-Android`, `X-Modelo` e `X-Relogio-Coletor`. Valor inválido é ignorado, e um header ausente mantém o último valor
guardado. A mudança do relógio (de certo para errado e de volta) aparece no log.

**QR de configuração (1.5.0):** o botão **QR de configuração** de cada coletor mostra um QR code para o app 2.0
configurar o servidor e a chave lendo o código, sem digitar. O texto do QR é
`RFIDCFG1|<host:porta>|<chave do coletor>|<SHA-256 do certificado>`, e o app confia no certificado se a impressão
digital bater. Como o QR leva a chave do coletor:
- só um ADMIN pode mostrar;
- o código só aparece depois do clique (POST com CSRF);
- cada vez fica na auditoria (área Coletores).

Endereço do servidor no QR: o de `RFID_ENDERECO_QR`, se estiver definido; senão o endereço pelo qual a tela foi
aberta; aberta em `localhost`, o IP da rede local desta máquina. A tela mostra o endereço usado, para conferir antes
de ler.

### Exportar para Excel

As telas de **Leituras**, **detalhe do inventário**, **Etiquetas** e **Não vistas** têm os botões **Excel** e
**CSV** (`/admin/leituras-android/exportar`, `/admin/inventarios/{id}/exportar`, `/admin/tags-cadastradas/exportar`
e `/admin/etiquetas/nao-vistas/exportar`, parâmetro `formato=xlsx|csv`), com a mesma permissão da tela. Toda
exportação fica na auditoria. Em Leituras, exporta **todas** as leituras do filtro aplicado (não só a página), até
100 mil; acima disso pede para filtrar mais. O log registra quem exportou, o filtro e quantas linhas.

- **Excel (.xlsx)**, o recomendado: textos ficam como texto (patrimônio `000123` não perde os zeros, EPC só com
  números não vira notação científica) e datas ficam como datas (dá para ordenar e filtrar).
- **CSV**: para importar em outros sistemas. Segue o padrão do Excel em português (`;` e UTF-8 com BOM), e texto
  que começa com `=`, `+`, `-` ou `@` ganha um `'` na frente, para não ser executado como fórmula.

### Erros do app nos coletores

O app guarda os travamentos e as falhas relevantes (envio recusado pelo servidor, erro do servidor na
sincronização, leitor RFID que não conecta, atualização que não baixou) e envia ao servidor quando há rede
(`POST /api/android/erros`, só a chave do coletor). Na tela **Coletores**, a coluna **Erros (24 h)** leva à lista de
erros do coletor, com a hora no coletor e a de chegada, o detalhe (stack trace) e o número de vezes. Dá para filtrar
por versão do app, para ver se o erro sumiu depois de uma atualização, e por tipo. O tipo `RELOGIO` (app 1.7.3+)
aparece como **Relógio do coletor**, e não como travamento. Até a 1.7.2, esse aviso vinha como `FALHA_GERAL`.

- Sem dados sensíveis: token da sessão, chave do coletor e senhas são mascarados no app e de novo no servidor.
- Contra app em loop de falha: o app agrupa os repetidos (número de vezes), o servidor aceita no máximo 50 por
  envio e `RFID_COLETORES_ERROS_POR_DIA` por coletor por dia (acima disso descarta, com um aviso por hora no log).
- Reenvio não duplica (o id é gerado no coletor). Erros com mais de `RFID_COLETORES_DIAS_ERROS` dias são apagados
  10 min depois de o servidor subir e a cada 24 h.

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

### Gestão de patrimônio (1.6.0)

- **Unidades e locais:** cada local pode pertencer a uma unidade (ex.: Matriz › Sala 101). O nome do local
  continua único no sistema inteiro, porque o coletor abre o inventário pelo nome. Local desativado sai das listas
  de escolha, mas os bens e os inventários dele continuam como estão.
- **Responsável e situação:** o cadastro da etiqueta ganha o local, o responsável (pessoa cadastrada em
  Responsáveis) e a situação (`Em uso`, `Em estoque`, `Em manutenção`, `Emprestado`, `Extraviado`). Ao finalizar
  um inventário, os bens encontrados passam a ter o local do inventário, e um bem `Extraviado` encontrado volta
  para `Em uso`. As duas mudanças ficam no histórico da etiqueta.
- **Baixa:** na tela de edição, com motivo (venda, doação, sucata, furto/roubo, perda, outro), data e observação.
  O bem baixado sai do esperado e do termo, mas continua no banco e a baixa pode ser desfeita. A lista de
  etiquetas só mostra os baixados com "Incluir baixados" ou com a situação `Baixado` no filtro.
- **Termo de responsabilidade:** página com os bens de um responsável (sem os baixados), pronta para imprimir ou
  salvar em PDF pelo navegador.
- **Importar planilha:** xlsx ou csv (`;` ou `,`, UTF-8 ou Windows-1252), até 5.000 linhas e 10 MB. As colunas são
  reconhecidas pelo cabeçalho (o modelo para baixar tem todas), e célula vazia mantém o valor atual. O envio só
  simula e mostra o que cada linha vai fazer (nova, atualizada, sem mudança ou erro). Nada é gravado até
  confirmar. Com "criar o que faltar", os tipos, marcas, modelos, unidades, locais e responsáveis que ainda não
  existem são criados. EPC em notação científica (ex.: `1,23E+23`, de uma planilha que virou número) é recusado.

### Transferência de bens (1.6.1)

Muda o local e/ou o responsável de um ou mais bens (até 1.000) e grava uma **guia numerada**, com a data, o
solicitante, o motivo e a origem de cada bem (local e responsável anteriores). A guia sai pronta para imprimir ou
salvar em PDF, com as assinaturas de quem entrega e de quem recebe. No histórico de cada bem, a mudança aparece com a
origem "Transferência nº X".

- Os bens são informados pela etiqueta (EPC) ou pelo patrimônio, um por linha (dá para colar uma coluna do Excel).
  "Conferir bens" mostra onde cada um está antes de transferir.
- Atalhos: botão **Transferir** na edição da etiqueta, **Transferir estes** na lista de etiquetas filtrada por local
  ou responsável (ex.: todos os bens de quem saiu da empresa) e **Transferir** nas divergências do inventário.
- Recusa a transferência inteira (nada muda) quando um código não existe, quando o bem está baixado, quando o bem já
  está no destino, quando o local ou o responsável está desativado ou quando a data está no futuro.
- Bem **extraviado** que muda de local volta para "Em uso": foi achado.
- **Pelo coletor (1.7.0):** no inventário, o operador com RFID · Editar toca num bem lido que é de outro local e
  escolhe "Transferir para este local". Gera a mesma guia (registrada pelo operador, com o motivo "Pelo coletor
  ..."), o mesmo histórico e a mesma auditoria. Só o local muda; o responsável fica.

### Divergências do inventário (1.6.1)

No detalhe de um inventário **finalizado**, quem pode editar etiquetas marca os bens que faltaram e escolhe o que
fazer: **transferir** (achados em outro lugar; a guia fica ligada ao inventário), **marcar como extraviados** ou
**dar baixa** (com o motivo). Cada bem mostra a situação atual no cadastro, ao lado da justificativa do coletor. A
ação vale só para os bens que faltaram naquele inventário, e no histórico de cada bem a origem diz de qual inventário
ela saiu. Os baixados e os transferidos para outro local saem da lista do que faltou; os extraviados continuam nela.
Etiqueta lida sem cadastro ganha o botão **Cadastrar**, que abre o cadastro já com o local do inventário.

### Valor de aquisição (1.8.0; o valor patrimonial saiu na 1.12.0)

O bem tem **valor de aquisição**, **data de aquisição** e **nota fiscal** (opcionais). Desde a 1.12.0 (locadora)
eles **não aparecem nas telas** e a tela Valor patrimonial, o PDF de bens com valor contábil e o valor no laudo do
inventário **saíram**. Os dados continuam no banco (a edição da etiqueta os manda de volta como estão) e na
importação e exportação da planilha (colunas `Valor de aquisição`, `Data de aquisição` e `Nota fiscal`). A vida útil
e o residual do tipo (V25) também ficam no banco, sem uso.

### PDFs (1.8.0)

Gerados pelo servidor (openhtmltopdf), abrem no navegador e trazem no rodapé quem gerou, quando e a versão:

- **Laudo do inventário** (botão no detalhe do inventário): o resumo, os bens que faltaram com a justificativa do
  coletor, os achados em outro local, as assinaturas e, em anexo, as etiquetas lidas. Inventário em andamento sai
  marcado como provisório.
- **Guia de transferência** e **termo de responsabilidade**: as mesmas páginas de imprimir, em PDF.
- **Contrato de locação** (1.12.0) e **ordem de manutenção** (1.11.0).

O laudo vai para a auditoria (Exportações), como as planilhas.

### Manutenção do bem (1.11.0)

Menu **Manutenções**, ou o botão **Manutenção** na edição da etiqueta. Cada **ordem de manutenção** é numerada e
vale para um bem: tipo (**corretiva** ou **preventiva**), o problema ou o que revisar, o fornecedor ou técnico, a
data de envio e a previsão de retorno.

- Só vai bem **Em uso** ou **Em estoque**. Com a ordem aberta, ele fica **Em manutenção**; ao **concluir** (resultado,
  serviço realizado, custo e data do retorno) ou **cancelar** (com o motivo), volta para a situação de antes.
- "Sem conserto" também devolve a situação anterior: a baixa (sucata) é uma decisão à parte, pela edição da etiqueta.
- A lista filtra **abertas**, **atrasadas** (previsão de retorno vencida), **concluídas e canceladas** ou todas, e
  busca por etiqueta, patrimônio ou fornecedor. A **ordem em PDF** acompanha o bem até o fornecedor.
- A edição da etiqueta mostra a ordem aberta e o **histórico de manutenções** do bem, com o **custo total**.
- Com a ordem aberta, o bem não recebe baixa, não é excluído, a situação não muda pela edição e ele não pode ser
  emprestado. Nas divergências do inventário, o bem em manutenção que faltou fica como está.
- Abertura, conclusão e cancelamento vão para o histórico do bem ("Manutenção nº X") e para a auditoria
  (Patrimônio). Um bem só tem uma ordem aberta por vez (índice único no banco, V28).

### Revisão automática por uso (1.21.0)

Em **Manutenções → Revisão automática**, cada tipo de equipamento pode ter uma regra: revisar **a cada N locações**
e/ou **a cada D dias** (quem tem Editar na Manutenção). A contagem começa na última **manutenção preventiva concluída
como "Consertado / revisado"** ou, sem nenhuma, no cadastro do bem. Os dois campos vazios desligam a regra.

- **Na conferência da devolução**, o equipamento que venceu a revisão vai direto para uma **ordem preventiva**
  ("Revisão automática: 5 locação(ões) desde a última revisão…"), em vez de voltar Disponível.
- **Na saída**, o equipamento Disponível com a revisão vencida (parado no estoque além dos dias) é **recusado**
  ("Revisão vencida"), e ele não entra na folha de separação.
- O **painel do dia** lista os vencidos no estoque, com o link para abrir a preventiva já preenchida, e o **resumo
  diário** dos avisos (8h) também.
- Concluir a preventiva como "Consertado / revisado" zera a contagem. Banco: V35 (`regras_revisao`).

### Avisos pelo Telegram e resumo diário da locação (1.20.0)

Os avisos que já iam por email (backup do banco com problema, cópia fora do computador falhando, servidor que ficou
fora do ar) vão também pelo **Telegram**, que funciona **sem o SMTP**. Os dois canais são independentes.

1. No Telegram, fale com o **@BotFather**, mande `/newbot` e guarde o **token** que ele devolve.
2. Mande uma mensagem qualquer para o robô novo (ou ponha o robô num grupo e escreva algo no grupo).
3. Ponha `rfid.alertas.telegram-token=<token>` no `config\application.properties` (ou `RFID_TELEGRAM_TOKEN`) e
   reinicie. Na tela **Status**, **Descobrir conversas do Telegram** mostra o número de cada conversa.
4. Ponha os números em `rfid.alertas.telegram-chats` (ou `RFID_TELEGRAM_CHATS`), separados por vírgula, e
   reinicie. **Enviar teste pelo Telegram** confere. O token nunca aparece no log nem na tela.

**Resumo diário da locação**: todo dia às 8h (`rfid.alertas.locacao-cron`), se houver **devolução atrasada** ou
**manutenção com retorno vencido**, sai um aviso com a lista (pedido, cliente, quantos equipamentos, dias de atraso)
pelos canais ligados. Sem nada atrasado, não manda nada. Sem migração.

### Cliente com restrição (1.19.0)

Em **Clientes**, o botão **Restrição** (só para administrador, gestor ou quem tem Excluir na Locação) marca o cliente:

- **Bloqueado**: não abre pedido novo (some da escolha no pedido novo) e **nada sai** para ele, nem nos pedidos já
  abertos. A **devolução continua normal**.
- **Só com aprovação**: o pedido abre, mas a **saída fica travada** até um administrador ou gestor clicar em
  **Aprovar pedido** na tela do pedido. Quem pode aprovar já sai com o pedido aprovado ao criá-lo. Trocar o cliente
  do pedido desfaz a aprovação.
- O **motivo** é obrigatório para restringir e aparece na lista de clientes e na recusa. Toda mudança vai para o log
  e para a auditoria (Patrimônio). Banco: V34 (colunas de restrição em `clientes` e de aprovação em `pedidos_locacao`).
- A versão completa (locação) vive na branch **locadora**, de onde sai o jar de produção. O master passou a ser o
  aplicativo simples de entrada e saída e inventário.

### Transferência só por local (1.18.2)

- O **responsável de destino** saiu da transferência: do formulário (onde já estava escondido), da rota e da coluna da
  lista. A transferência pela tela pede o **local de destino** (obrigatório).
- A guia (tela e PDF) só mostra "Responsável de destino" e "Responsável anterior" nas guias antigas que mudaram o
  responsável, para não perder o histórico. O responsável continua no cadastro da etiqueta e no banco.

### Limpeza e organização do código (1.18.1)

Sem mudança no uso nem no banco (nenhuma migração; os dados antigos ficam como estão).

- **Saiu o código sem uso:**
  - a API de saída e devolução pelo coletor (`/api/android/pedidos`, `/conferir`, `/saida`, `/devolucoes`): o coletor
    só faz inventário e consulta, e a saída e a devolução são pela web;
  - a tela de Responsáveis e o termo de responsabilidade (tela e PDF), que estavam fora do menu desde a 1.12.0;
  - a regra de depreciação e os campos dela no tipo de equipamento.
- **Código organizado:**
  - os nomes completos de classe no meio do código (`org.example.service.X`) viraram imports;
  - os imports sem uso saíram, e os imports estão em ordem;
  - os tipos da locação foram para `LocacaoTipos.kt`, e o `FormatoBr` foi para o próprio arquivo.
- Continuam no banco e no cadastro da etiqueta: o responsável, o valor, a data de aquisição e a nota fiscal; e as
  tabelas antigas de empréstimos (os dados já estão nos pedidos).

### Kits e acessórios (1.18.0)

- **Acessórios e kits** (menu, Locação · Ver; cadastrar e pôr no kit: Criar; alterar e tirar do kit: Editar):
  cadastro dos acessórios (carregador, bateria, berço, cabo...), que são **contados**, sem etiqueta. O **kit** diz
  quantos de cada acessório acompanham um equipamento de um **tipo**, ou de um **modelo** (o kit do modelo vale antes
  do kit do tipo). Acessório desativado não entra em kits nem em pedidos novos (V33).
- No **pedido**, ao acrescentar uma linha, o kit entra junto (quantidade do kit × quantidade da linha); dá para
  acrescentar acessório **avulso**. Para cada acessório ficam o pedido, os **enviados**, os **devolvidos** e o que
  **falta** voltar, com os botões "Tudo enviado" e "Tudo devolvido". Devolvido não passa do enviado; o que já foi
  enviado não sai do pedido; com o pedido encerrado só a devolução muda (acessório que chegou depois). Tirar a linha
  leva junto os acessórios dela que não foram enviados.
- O pedido com os equipamentos de volta e acessório faltando mostra o aviso "Faltam N acessório(s)". A **folha de
  separação** lista os acessórios a separar; o **contrato** lista os acessórios com enviados e devolvidos; os
  **Relatórios da locação** mostram os **acessórios que não voltaram** (pedidos encerrados ou com a devolução vencida).

### Folha de separação e relatórios da locação (1.17.0)

- **Folha de separação** (botão no pedido enquanto falta sair algo, Locação · Ver): PDF com o cliente, o local de
  entrega e as datas, e, para cada item que ainda falta, quantos separar e os equipamentos **Disponíveis** que servem,
  com o **local** de cada um e uma caixa para marcar (até 30 por item; um equipamento aparece em um item só). Espaço
  para "Separado por" e "Conferido por".
- **Relatórios da locação** (menu, Locação · Ver), por período (padrão: últimos 90 dias):
  - **Uso por modelo:** equipamentos no acervo, pedidos, dias alugado no período e a **ocupação** (dias com clientes ÷
    equipamentos × dias do período).
  - **Parados:** Disponíveis sem sair há N dias ou mais (padrão 60; nunca alugado conta desde o cadastro), com link para
    a linha do tempo: candidatos à venda ou realocação.
  - **Avarias e manutenção por modelo:** devoluções conferidas, com avaria, faltando acessório, manutenções e o custo.
  - **Pontualidade dos clientes:** devoluções, quantas atrasaram (contra a devolução prevista já com as prorrogações),
    o atraso médio e quantos equipamentos estão atrasados agora.

### Linha do tempo do equipamento e prorrogação do pedido (1.16.0)

- **Linha do tempo** (botão na edição da etiqueta, Etiquetas · Ver): tudo o que aconteceu com o equipamento numa
  lista só, do mais novo para o mais antigo: cadastro e alterações feitas à mão, cada saída e devolução de locação
  (com o cliente, o local de entrega e os dias de atraso), a conferência (avarias em destaque), as manutenções com o
  resultado e o custo, as transferências, os inventários que o encontraram e as vendas. No topo, o resumo: quantas
  locações, para quantos clientes, quantos dias alugado, quantas manutenções e o custo total, quantas avarias e o
  último cliente. As mudanças de situação que vêm desses fluxos não aparecem de novo como alteração.
- **Prorrogar o pedido** (na tela do pedido, Locação · Editar): nova devolução prevista e o motivo (obrigatório).
  Cada mudança fica no histórico do pedido (data de antes, nova, motivo, quem e quando), no contrato em PDF e na
  auditoria; mudar a data pela edição dos dados do pedido também entra no histórico (V32). Não aceita data no
  passado nem pedido encerrado ou cancelado.

### Painel do dia e disponibilidade (1.13.0)

Pensados para o uso interno da equipe. Só leitura, com RFID · Ver.

- **Painel do dia** (tela **Início**): quantos equipamentos estão disponíveis, alugados, esperando a conferência e em
  manutenção; os pedidos a **separar e entregar** (saída prevista até amanhã, já passada ou sem data, com quantos
  faltam); as **devoluções** atrasadas e as previstas para hoje; e as **manutenções** com o retorno atrasado. Cada
  número leva à tela correspondente.
- **Disponibilidade** (menu): por tipo e por marca/modelo, quantos estão disponíveis, alugados, em devolução, em
  manutenção e à venda; quantos alugados **voltam até** uma data escolhida; quantos já estão **comprometidos** nas
  vagas dos pedidos a separar; e os **livres** (disponíveis menos os comprometidos, por tipo; negativo = os pedidos
  abertos pedem mais do que há). Filtro por tipo.

### Locadora: clientes, pedidos de locação e venda (1.12.0)

Desde a 1.12.0 o sistema é de **locadora**: os equipamentos são alugados a clientes, vendidos ou voltam do aluguel.
O empréstimo para responsável da 1.10.0 virou o pedido de locação (a V29 converteu os empréstimos em pedidos com o
mesmo número e os responsáveis deles em clientes).

**Situações do equipamento:** Disponível, Alugado, Em devolução, Em manutenção, À venda, Vendido, Extraviado e
Baixado. Na edição só se escolhe Disponível, À venda, Em manutenção ou Extraviado; as outras vêm dos pedidos, da
venda e da baixa. A V29 trocou Em uso e Em estoque por **Disponível** e Emprestado por **Alugado** (a planilha e o
app antigos ainda podem mandar os nomes antigos). Vendido e Baixado saem das listas e do esperado dos inventários;
Alugado sai do esperado (está com o cliente).

**Fora da tela:** responsável, valor de aquisição, data de aquisição e nota fiscal (decisão do usuário). Os dados
continuam no banco (a edição os manda de volta como estão). As telas Responsáveis, Termo de responsabilidade e Valor
patrimonial saíram (a do valor na 1.12.0; Responsáveis e o termo, com as rotas, na limpeza da 1.18.1).

- **Clientes** (menu Clientes): nome, CPF ou CNPJ (sem repetir), contato, telefone, email, endereço e observação.
- **Pedido de locação** (menu Pedidos de locação): cliente, saída prevista, **devolução prevista**, local de entrega
  (vazio = o endereço do cliente) e as **linhas**: N de um tipo (marca e modelo opcionais: qualquer um serve) ou um
  equipamento específico.
- **Saída** pelo coletor (o operador abre o pedido e lê; o app mostra o que falta e o que sobra) ou pela web. É
  parcial e soma ao que já saiu: o pedido fica Em separação até preencher as vagas (ou a web encerrar a separação) e
  então Entregue. O equipamento fica **Alugado**. Recusados: sem cadastro, fora do pedido, não disponível, excedente.
- **Devolução** pelo coletor ou pela web: o equipamento fica **Em devolução**; o pedido fica Devolvido em parte ou
  Encerrado.
- **Conferência da devolução** (fila em Pedidos › Devoluções a conferir, ou no pedido): o estado (sem avarias, com
  avaria, faltando acessório) e o destino: **Disponível** ou a **manutenção** (abre a ordem com o que foi observado).
  Fotos: nos anexos da etiqueta.
- **Contrato de locação** em PDF, e aviso de **devolução atrasada** (lista e pedido).
- **Venda** (edição da etiqueta, equipamento Disponível ou À venda): cliente, valor, data e observação; fica
  **Vendido**. Desfazer volta para À venda.
- Do momento da saída até a conferência, o equipamento não recebe baixa, não é excluído, não é vendido e a situação
  não muda pela edição; e só está em um pedido por vez (índice único no banco).
- Tudo vai para o histórico de cada equipamento e para a auditoria (Patrimônio).
- Saída e devolução são feitas pela web. O coletor só faz inventário e consulta (a ficha mostra o pedido e o
  cliente); a API de saída e devolução pelo coletor saiu na limpeza da 1.18.1.

### Fotos e anexos do bem (1.9.0)

Na edição da etiqueta, o card **Fotos e anexos** guarda a foto do equipamento, a nota fiscal, o laudo etc.

- Aceita fotos **JPEG, PNG e WebP** e **PDF**, reconhecidos pelos primeiros bytes do arquivo (não pela extensão):
  um arquivo de outro tipo é recusado, e o nome ganha a extensão do tipo real.
- Até **10 MB** por arquivo e **20** por bem (`RFID_ANEXOS_MAX_BYTES` e `RFID_ANEXOS_MAX_POR_BEM`).
- Os arquivos ficam **no banco** (V26, tabelas `anexos_bem` e `anexos_bem_conteudo`): o backup diário e a cópia
  cifrada fora do computador já os levam. Cada foto aumenta o tamanho dos dumps.
- Enviar e excluir vão para o histórico da etiqueta (campo "Anexo", com o nome e o tamanho). Excluir a etiqueta
  apaga os anexos dela; os nomes continuam no histórico.
- O envio só é aceito de quem tem RFID · Editar, conferido antes de o servidor ler o arquivo (EnvioArquivoFilter).

### Inventário: o que faltou

O detalhe do inventário mostra o que **faltou** em relação ao **inventário finalizado anterior do mesmo local**
(o último finalizado antes do início deste; um inventário antigo compara com o que veio antes dele), com a data e
o operador da referência. Entram as etiquetas cadastradas encontradas na referência, com os dados do cadastro atual
(etiquetas excluídas depois ficam de fora). Inventário em andamento mostra "Faltando até agora" (lista
provisória); o primeiro inventário de um local diz que não há comparação. A exportação traz as lidas e, depois,
as que faltaram, com a situação `Faltando` (ou `Faltando até agora`). Desde a 1.6.0, também entram como esperados
os bens cujo local no cadastro é o do inventário, mesmo que a referência não os tenha (a mesma regra do esperado
do coletor, abaixo), e os baixados ficam de fora.

### Perfis de usuário

| Perfil | Permissões |
|---|---|
| **Administrador** | Tudo, inclusive usuários, backup e console do banco. |
| **Gestor** | Ver, criar e editar em todos os módulos. Não exclui e não gerencia usuários. |
| **Usuário** | Só consulta (ver) em todos os módulos. |
| **Expedição** (1.15.0) | Locação: ver, criar e editar (pedidos, clientes, saída e devolução). Ver etiquetas, leituras e catálogo. |
| **Conferente** (1.15.0) | Locação: ver e editar (devolução e conferência). Manutenção: ver e abrir. Etiquetas: ver e editar (fotos). Ver leituras e catálogo. |
| **Técnico** (1.15.0) | Manutenção: ver, abrir, concluir e cancelar. Etiquetas: ver e editar (fotos). Ver locação, leituras e catálogo. |
| **Personalizado** | Os módulos e ações escolhidos no cadastro. |

Módulos: Catálogo, Etiquetas (RFID), Leituras, Relatórios, **Locação** e **Manutenção** (1.15.0), Usuários. Ações:
Ver, Criar, Editar, Excluir. Os perfis de função (Expedição, Conferente, Técnico) têm as permissões prontas por módulo,
para o uso interno; as caixas do formulário não valem para eles.

Na Locação: ver = listas, pedidos, disponibilidade e o painel do dia; criar = pedido, linha e cliente; editar = saída,
devolução, conferência, encerrar e cancelar. Mandar para a manutenção na conferência pede também Manutenção · Criar.
Na Manutenção: criar = abrir a ordem; editar = concluir e cancelar. A V31 deu Locação e Manutenção a quem já tinha
Etiquetas, com as mesmas ações (até a 1.14.0 essas telas pediam as permissões de Etiquetas).
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
   RFID · Criar para cadastrar, RFID · Editar para transferir). A resposta do login diz o que o operador pode
   fazer: `podeLer`, `podeCadastrar` e, desde a 1.7.0, `podeTransferir`.

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
| `GET /api/android/tags/{epc}` | Ficha do bem: o cadastro e, desde a 1.5.0, `ultimoLocal` e `ultimoInventarioEm` (último inventário finalizado em que foi encontrado), `ultimaLeituraEm` e `ultimaLeituraPor` (opcionais, podem vir `null`); desde a 1.6.0, também `situacao` (código), `situacaoRotulo`, `unidade`, `local` e `responsavel` (opcionais) | operador · Leituras |
| `GET /api/android/tags/patrimonio/{patrimonio}` | EPC de um patrimônio (localizar equipamento) | operador · Leituras |
| `POST /api/android/tags/{epc}/transferencia` | Transfere o bem para um local (1.7.0): `{"local","observacao"}`. Só o local muda. Responde `{"local","situacao","situacaoRotulo","transferencia","jaEstava"}`; o bem que já estava no local responde 200 com `jaEstava: true` e sem guia (reenvio). 404 etiqueta sem cadastro, 409 bem baixado, 400 local desconhecido ou desativado | operador · RFID Editar |
| `POST /api/android/leitura` | Registra a leitura de uma etiqueta | operador · Leituras |
| `POST /api/android/naocadastrada` | Envia etiqueta desconhecida para Pendentes | operador · Leituras |
| `POST /api/android/leituras/lote` | Até 500 leituras de um operador numa requisição: classifica e registra cada uma e devolve o resultado por item (`CADASTRADA` com os dados, `NAO_CADASTRADA`, `RECUSADA` ou `ERRO`). Desde a 1.6.2, os dados trazem também `situacao` e `situacaoRotulo`, com os mesmos nomes da ficha (o app avisa quando o bem lido está baixado ou extraviado). A hora de cada leitura é conferida contra a sessão; reenvio da mesma tag com a mesma hora não duplica | operador · Leituras |
| `GET /api/android/catalogo` | Catálogo para o formulário de cadastro | operador |
| `POST /api/android/tags` | Cadastra etiqueta pelo coletor | operador · RFID Criar **e** coletor em `RFID_COLETORES_CADASTRO` |
| `GET /api/android/locais` | Locais já usados em inventários (desde a 1.6.0, só os ativos) | operador · Leituras |
| `POST /api/android/inventarios` | Abre inventário num local | operador · Leituras |
| `POST /api/android/inventarios/{id}/itens` | Envia etiquetas lidas | operador · Leituras |
| `POST /api/android/inventarios/{id}/finalizar` | Finaliza o inventário | operador · Leituras |
| `POST /api/android/inventarios/{id}/justificativas` | Justificativa dos bens que faltaram (1.5.0): `[{"tag","motivo","observacao"}]`. Motivos: `EMPRESTADO`, `MANUTENCAO`, `BAIXADO`, `NAO_ENCONTRADO`, `OUTRO` (ou o nome, como "Em manutenção"). Aceita depois de finalizado e é idempotente. Responde `{"recebidas","gravadas","invalidas"}` | operador · Leituras |
| `GET /api/android/inventarios/esperado?local=...` | O que é esperado no local, para o coletor mostrar o que falta (ver abaixo). Desde a 1.6.1, cada item traz também `situacao` e `situacaoRotulo` (situação do bem no cadastro, opcionais) | operador · Leituras |

**O que é esperado num local:** desde a 1.6.0, as etiquetas cujo **local no cadastro** é esse, somadas às
**cadastradas** encontradas no **último inventário finalizado** daquele local que o cadastro não pôs em outro
local. Bens baixados ficam de fora. O nome do local segue a regra do inventário (ignora maiúsculas e espaços extras). Etiquetas excluídas do cadastro depois ficam de fora, e os dados (patrimônio, tipo,
marca, modelo) são os do cadastro atual. Local sem inventário finalizado e sem bens no cadastro responde
`"temReferencia": false` e a lista vazia (primeiro inventário dele). A resposta traz também a data e o operador do inventário de referência.

Desde a 1.5.0, um bem que estava no inventário de referência e foi encontrado **depois** num inventário finalizado de
outro local sai do esperado. Ele foi levado para lá, e o local do bem passa a ser o do inventário finalizado mais
recente que o encontrou. No detalhe do inventário na web, esses bens aparecem à parte, em **Encontrados em outro
local**, e não como faltando. As justificativas marcadas no coletor aparecem ao lado de cada bem que faltou e também
vão para a exportação.

A resposta tem `ETag`, o hash do próprio JSON. Na próxima vez, o coletor manda a lista que guardou com
`If-None-Match: <ETag>`. Se nada mudou, a resposta é **304 sem corpo**, e as etiquetas não passam de novo pelo Wi-Fi.
O hash muda com qualquer mudança no cadastro ou com um inventário novo finalizado.

Erros sempre em JSON: `{"status": 400, "mensagem": "...", "codigo": null, "caminho": "..."}`.
401 = chave ou sessão inválida · 403 = sem permissão (ou inventário aberto por outro coletor) · 409 = conflito (ex.: item em uso) ·
410 = rota antiga removida (atualize o app) · 426 = chamada por HTTP (use HTTPS) · 429 = login bloqueado por tentativas (header `Retry-After`).

O 401 de sessão do operador vem com `"codigo": "SESSAO_OPERADOR"` e `"motivo"`: `DESCONHECIDA` (token que o
servidor não conhece, ex.: banco restaurado), `OUTRO_COLETOR`, `ENCERRADA` (depois do "Sair"), `EXPIRADA`,
`ANTIGA_DEMAIS` (chegou depois do prazo de sincronização), `USUARIO_INEXISTENTE`, `DESATIVADO` ou `SEM_OPERADOR`.
Os dois primeiros apontam problema do servidor ou do coletor, não do operador. No lote, os itens `RECUSADA` por
sessão trazem o mesmo `motivo`.

Fim da sessão pelo "Sair" ou por outro login no mesmo coletor: a hora informada pelo coletor (`X-Momento` ou
`lidoEm`) tem uma margem de **5 min** depois do fim. É a mesma diferença de relógio que o servidor aceita: acima de
5 min no futuro, a hora do coletor vira "agora". Assim, com o relógio do coletor adiantado, as leituras do último
minuto não são recusadas como `ENCERRADA`. Na 1.4.4 a margem era de 2 min, e com o relógio adiantado entre 2 e 5 min
essas leituras eram perdidas. Sem hora do coletor, vale a hora do servidor, sem margem. A sessão encerrada pelo
servidor (desativação, senha temporária ou senha trocada) não tem margem.

> As rotas antigas dos coletores (`/api/tags/...` e `/api/rfid/...`) foram **removidas**: respondem
> **410** explicando que o app precisa ser atualizado, e o servidor registra no log (uma vez por hora por
> coletor) quem ainda as chama — útil para achar coletor com app antigo.

---

## Segurança

| Proteção | Como funciona |
|---|---|
| **HTTPS** | Porta 8443 com certificado próprio gerado na primeira execução (10 anos). A porta HTTP 8082 só redireciona as telas e recusa a API com 426. Os coletores fixam a **impressão digital** do certificado, mostrada no log a cada inicialização e em `/status` (só ADMIN). |
| **Configuração inicial** | `/setup` só funciona com o banco vazio; depois ninguém cria outro administrador por ali. |
| **Senhas** | Mínimo 8 caracteres, letras e números, sem o nome/email, sem senhas comuns, até 72 bytes. Quem entra com senha antiga fora da regra só acessa a troca de senha até criar uma nova (na web; o coletor só avisa). Senhas guardadas com BCrypt. |
| **Tentativas de login** | Em 15 min: 5 erros no mesmo email vindos da mesma origem bloqueiam aquele login só para essa origem (quem erra a senha do ADMIN de outra máquina não tranca o ADMIN); 20 erros no mesmo email somando as origens, ou 20 na mesma origem em qualquer conta, bloqueiam por 15 min. A origem é o IP na web e o **coletor + IP** no coletor: vários coletores atrás do mesmo IP (NAT) não se bloqueiam por causa de um operador errando a senha. Durante o bloqueio nem a senha certa entra. Email inexistente recebe a mesma resposta que senha errada. |
| **Permissões** | Por módulo e ação, conferidas em cada rota (não só escondendo botões). |
| **Usuário desativado** | Não entra na web nem no coletor, e as sessões abertas dele (web e coletores) caem na hora. Também caem as outras sessões quando a pessoa troca a própria senha ou o ADMIN gera uma senha temporária. O ADMIN não desativa a si mesmo nem o último ADMIN ativo. |
| **CSRF** | Ativo em todas as telas; a API dos coletores não usa cookie, então não precisa. |
| **Content-Security-Policy** | As telas só carregam scripts, estilos, imagens e conexões do próprio servidor, não abrem plugins e só enviam formulários para ele. Tudo é local (Chart.js e ícones inclusive), então funciona sem internet. |
| **Rotas /admin** | Um teste confere que toda rota em `/admin` tem `@PreAuthorize` (sem isso ela abriria para qualquer usuário logado). |
| **Emails** | Gravados sem espaços e em minúsculas; o login (web e coletor) não diferencia maiúsculas. |
| **Sessões do coletor** | Token aleatório de 32 bytes; o banco guarda só o hash SHA-256. |
| **Integridade** | Chaves estrangeiras impedem excluir tipo/marca/modelo usado por etiquetas. |
| **Banco** | O PostgreSQL só aceita conexão da própria máquina (`127.0.0.1:5433`); a senha fica no `docker/.env` e no `config\application.properties`, fora do git. |
| **Prazos do banco** | O servidor espera no máximo 5 s por uma conexão do banco, 60 s pela resposta e 60 s por comando SQL (`statement_timeout`: o PostgreSQL cancela o que passar disso e o erro aparece no log). Conexões paradas são testadas a cada 2 min. As migrações usam conexão própria, sem esse limite, e a retenção de leituras apaga em lotes de 10.000. |
| **Conexões com o banco (1.5.2)** | Até 30 conexões ao mesmo tempo (`RFID_DB_POOL`; eram 10), para 20 ou 30 coletores sincronizando juntos no fim do turno. A API dos coletores (`/api/android`) e o fluxo ao vivo do painel (`/admin/sse`) não usam o "open in view": a conexão fica presa só enquanto o serviço trabalha, e não durante a requisição inteira (um lote de 500 leituras, ou enquanto a aba do painel fica aberta). As telas continuam com ele, porque leem dados lazy ao montar o HTML (`SessaoJpaTelasConfig`). |
| **Sessão das telas** | Acaba após 30 min sem uso (`RFID_SESSAO_WEB_TIMEOUT`). A atualização em tempo real do dashboard não conta como uso: uma aba esquecida aberta volta ao login depois desse tempo. |
| **Tamanho das requisições** | Requisições das telas acima de 1 MB recebem 413 (`RFID_WEB_MAX_CORPO_BYTES`); a API dos coletores tem limite de 8 MB e o envio de APK, 100 MB. Emails acima de 255 caracteres no login são recusados como login errado. |
| **Arquivos do certificado** | O `.senha` do certificado fica legível só pelo dono, SYSTEM e Administradores (Windows) ou com permissão 600 (Linux). O backup é montado na memória e só vai para a pasta no formato final; sobras `rfid-certificado-*.parcial` de um backup interrompido são apagadas sozinhas, com aviso no log. |

---

## Banco de dados, migrações e backup

- **PostgreSQL 16 no Docker** (`docker/docker-compose.yml`): contêiner `rfid-postgres` na porta **5433** (só a
  própria máquina conecta), dados no volume `rfid-pgdata`. Os contêineres voltam sozinhos quando o Docker inicia.
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
  | V10 | `db/migration/V10__leitura_unica.sql` | A mesma leitura (etiqueta + hora no coletor) só é gravada uma vez, mesmo com reenvio simultâneo |
  | V11 | `db/migration/V11__usuario_ativo.sql` | Usuário ativo/desativado |
  | V12 | `db/migration/V12__email_minusculo.sql` | Emails já cadastrados em minúsculas e sem espaços |
  | V13 | `db/migration/V13__auditoria.sql` | Auditoria de usuários e configurações |
  | V14 | `db/migration/V14__versao_app_retirada.sql` | Versão do app retirada pelo ADMIN |
  | V15 | `db/migration/V15__busca_leituras_trigram.sql` | Índices trigram (`pg_trgm`) para os filtros "contém" da tela de leituras |
  | V16 | `db/migration/V16__leitura_hora_do_coletor.sql` | Hora informada pelo coletor em cada leitura (reconhece o reenvio mesmo com o relógio do coletor adiantado) e índice do último inventário por etiqueta |
  | V17 | `db/migration/V17__versao_etiqueta_e_nomes_unicos.sql` | Versão de cada etiqueta (edição ao mesmo tempo é avisada) e nomes de local e catálogo únicos sem diferenciar maiúsculas |
  | V18 | `db/migration/V18__epc_maiusculo.sql` | Etiqueta (EPC) sempre em maiúsculas e sem espaços nas pontas (a mesma etiqueta gravada nas duas formas fica como está e é avisada no log ao subir) |
  | V19 | `db/migration/V19__senha_temporaria_prazo_e_leitura_unica.sql` | Prazo da senha temporária (48 h, `RFID_SENHA_PRAZO_TEMPORARIA`) e índice único (etiqueta, hora) só para leituras sem a hora do coletor |
  | V20 | `db/migration/V20__indice_leituras_tag_data_hora.sql` | Índice comum (não único) (etiqueta, hora) das leituras, usado pela última leitura de cada etiqueta (relatório "não vistas" e exportação do cadastro) |
  | V22 | `db/migration/V22__justificativas_inventario.sql` | Justificativas dos bens que faltaram no inventário (`inventario_justificativas`) e índice por etiqueta nos itens de inventário (ficha do bem e esperado sem os bens achados em outro local) |
  | V21 | `db/migration/V21__diagnostico_coletor_e_motivo_fim_sessao.sql` | Diagnóstico do coletor (fila, última sincronização, espaço, Android, modelo, desvio do relógio) e por que a sessão do operador acabou (`encerrada_por`: margem de relógio só para "Sair" e outro login) |
  | V23 | `db/migration/V23__gestao_patrimonio.sql` | Gestão de patrimônio: unidades, locais com unidade e ativo, responsáveis, e local, responsável, situação e baixa no cadastro da etiqueta |
  | V24 | `db/migration/V24__transferencias.sql` | Transferências de bens: a guia (`transferencias`, com o destino pelo id e pelo nome) e os bens dela com a origem (`transferencia_itens`) |
  | V25 | `db/migration/V25__valor_depreciacao.sql` | Valor e depreciação: vida útil (meses) e residual (%) no tipo de equipamento; valor, data de aquisição e nota fiscal no bem |

  > **Atualizar uma instalação com muitas leituras (centenas de milhares ou mais):** a V16 reescreve a tabela de
  > leituras inteira ao subir, numa transação só: o servidor fica alguns minutos sem responder e a tabela cresce no
  > disco. Atualize fora do horário de uso e, depois, rode
  > `docker exec rfid-postgres psql -U rfid -d rfid -c "vacuum analyze leituras_android"`.
  >
  > Nomes repetidos que só diferem em maiúsculas (locais, catálogo) impedem a V17 de criar a proteção contra
  > duplicata: o servidor avisa no log ao subir e cria a proteção sozinho quando as repetições forem resolvidas.

- **Backup do banco:** o contêiner `rfid-backup` roda o `pg_dump` ao subir e depois de 6 em 6 horas enquanto está no
  ar (horário fixo de madrugada não serve: com o computador desligado o backup nunca roda) e guarda em
  `docker/backups` (ou na pasta de `RFID_BACKUP_PASTA` do `.env`), em `daily/`, `weekly/` e `monthly/`, mantendo
  14 diários, 4 semanais e 6 mensais. Backup na hora: `docker exec rfid-backup /backup.sh`. Ao religar o
  computador, ele espera o banco aceitar conexões (até 5 min) antes do backup ao subir. A tela **Status** mostra o
  último backup do banco (lido de `docker\backups\last`) e um alerta quando passa de 12 h sem backup ou quando a
  última tentativa gerou um arquivo vazio ou incompleto; o mesmo aviso vai para o log a cada 6 h.
  Desde a 1.4.6, cada dump é lido inteiro uma vez (`pg_restore -f /dev/null` no contêiner, sem gravar nada;
  `RFID_BANCO_CONFERIR_DUMP`, vazio = só o tamanho). Um dump cortado, por exemplo com o computador desligado no meio
  do backup, aparece como incompleto, e passa a valer o anterior inteiro. Sem o Docker, a tela diz "integridade não
  conferida". O `pg_restore --list` não serve para isso: ele aceita um dump que perdeu só o final.
- **Cópia do banco fora do computador (1.5.1):** de hora em hora, o último dump inteiro é cifrado com a senha do
  backup (`RFID_BACKUP_SENHA`, a mesma do certificado) e gravado na pasta de `RFID_BANCO_COPIA_EXTERNA`, por exemplo
  `${OneDriveCommercial}/Backups RFID/banco`, que sobe sozinha para a nuvem. O servidor guarda as 14 cópias mais
  recentes (`RFID_BANCO_COPIA_MANTER`).
  - **Sem a senha, não copia:** o dump leva todos os dados e os hashes das senhas e não pode ir aberto para a nuvem.
  - **Tela Status:** mostra a cópia mais recente e avisa se ela falhar ou ficar mais de 24 h sem cópia nova.
  - **Sem a variável do OneDrive (1.15.0):** se `OneDriveCommercial` (ou `OneDrive`, `OneDriveConsumer`) não chegar ao
    processo do servidor, ele sobe assim mesmo: a cópia externa fica **desligada** (o Status mostra "INDISPONÍVEL") e o
    backup do certificado vai para `data\backups`, com aviso no log. Antes, o servidor não subia ("Could not resolve
    placeholder"). Reinicie o servidor com o OneDrive ativo para voltar ao normal.
  - **Restaurar a partir da cópia:** decifre com `java -jar servidor\rfid-server.jar --decifrar-backup rfid-AAAAMMDD-HHMMSS.dump.cifrado`
    (pede a senha). Depois copie o `.dump` decifrado para `docker\backups\last` e restaure como abaixo, trocando o
    nome do arquivo.
- **Avisos por email (1.5.1):** mandam email quando:
  - o backup do banco atrasa ou sai incompleto;
  - a cópia fora do computador falha ou atrasa;
  - o servidor fica fora do ar ou parado (computador suspenso).

  O servidor grava a hora a cada 5 min (`data\servidor-no-ar.txt`). Ao voltar, se passou de 30 min (`RFID_MINUTOS_FORA`),
  ele avisa de quando a quando ficou fora. Com o servidor fora do ar nada roda, então o aviso sai quando ele volta.
  - **Repetição:** cada problema avisa quando aparece, repete no máximo a cada 24 h e avisa quando é resolvido.
  - **Para ligar:** defina o servidor de email (`RFID_SMTP_HOST`, `RFID_SMTP_PORTA`, `RFID_SMTP_USUARIO`,
    `RFID_SMTP_SENHA`) e os destinatários (`RFID_ALERTAS_EMAIL`). A tela **Status** mostra se os avisos estão
    ligados e tem o botão **Enviar email de teste**.
  - **Sem SMTP:** os avisos ficam no log, como antes. O `/actuator/health` não confere o email.
- **Backup antes de migrar:** quando uma versão nova do servidor traz migração, o servidor roda
  `docker exec rfid-backup /backup.sh` antes de aplicá-la (`RFID_BACKUP_ANTES_MIGRACAO`). Se esse backup falhar,
  a migração não é aplicada e o servidor não sobe: o log diz o motivo (normalmente o Docker Desktop fechado).
  Para migrar sem backup, defina `RFID_BACKUP_ANTES_MIGRACAO` vazio. Banco vazio (primeira instalação) não faz.

  **Restaurar o banco** (apaga os dados atuais do banco): pare o servidor e rode, na pasta `docker`:

  ```powershell
  docker compose exec backup sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists /backups/last/rfid-latest.dump'
  ```

  Roda dentro do contêiner de backup, que já enxerga a pasta dos backups: o `rfid-latest.dump` é um atalho
  (link simbólico) para o último backup, e o `docker cp` do Windows não consegue copiar atalhos. Para outro dia,
  troque o caminho, ex.: `/backups/daily/rfid-20261019.dump`.

  **Conferir um backup sem mexer no banco** (restaura num banco à parte e apaga depois):

  ```powershell
  docker exec rfid-postgres psql -U rfid -d rfid -c "create database rfid_teste_restauracao"
  docker compose exec backup sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d rfid_teste_restauracao /backups/last/rfid-latest.dump'
  docker exec rfid-postgres psql -U rfid -d rfid_teste_restauracao -c "select count(*) from usuarios"
  docker exec rfid-postgres psql -U rfid -d rfid -c "drop database rfid_teste_restauracao"
  ```

- **Backup do certificado HTTPS:** o servidor guarda `rfid-https.p12` e `.senha` todo dia às 02:00 em
  `./data/backups` (`rfid-certificado-<data>.zip`), mantendo os 14 mais recentes. Ao subir, se não houver backup
  das últimas 24 h, faz um na hora; o ADMIN também pode fazer pelo botão em `/status`. **Restaurar:** pare o
  servidor e extraia o `.zip` em `./data`: os coletores continuam reconhecendo o servidor sem nova confirmação.

  > Copie as duas pastas de backup para **outro disco ou pasta de rede**: backup no mesmo disco não protege contra
  > falha do disco. O zip do certificado tem a chave privada e a senha dela, e o dump tem os dados: acesso só para
  > quem administra o servidor. Senha informada em `RFID_HTTPS_SENHA` não vai para o backup: guarde-a à parte.
  > **Defina a senha do backup:** só com `RFID_BACKUP_SENHA` (ou `rfid.backup.senha` no
  > `config\application.properties`) o backup sai cifrado; sem ela o `.zip` sai aberto, com a chave privada, e o log
  > avisa ao subir. **Guarde essa senha fora desta máquina** (cofre de senhas, papel guardado): se o disco morrer,
  > sem ela o backup não restaura e os coletores terão de confirmar um certificado novo.
  > Com `RFID_BACKUP_SENHA` definida, o backup sai cifrado (`rfid-certificado-<data>.zip.cifrado`). Para restaurar,
  > decifre antes com `java -jar servidor\rfid-server.jar --decifrar-backup <arquivo>.zip.cifrado` (pede a senha) e
  > extraia o `.zip` gerado em `./data`.

---

## Instalação e primeira execução

Requisitos: **Java 17** (para rodar), **Docker Desktop** (para o banco) e o Gradle wrapper do projeto (para compilar).

1. **Banco:** copie `docker/.env.exemplo` para `docker/.env`, troque a senha e suba o banco e o backup:
   ```powershell
   cd docker
   docker compose up -d
   ```
   No Docker Desktop, deixe ligado *Start Docker Desktop when you sign in*: assim o banco volta junto com o Windows.
2. **Senha no servidor:** em `config\application.properties` (ou `RFID_DB_PASSWORD`), `spring.datasource.password=` com
   a mesma senha do `.env`.
3. **Servidor:**
   ```bash
   ./gradlew bootJar                      # gera build/libs/rfid-server-<versão>.jar
   java -jar build/libs/rfid-server-<versão>.jar
   ```

Na primeira execução o servidor:

1. cria as tabelas no PostgreSQL (migrações do Flyway);
2. gera o certificado HTTPS em `./data/rfid-https.p12` (veja a impressão digital no log ou em `/status`, só ADMIN);
3. faz o primeiro backup do certificado.

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

> Execute o servidor sempre a partir da mesma pasta (o certificado, os logs e os backups dele ficam em `./data`,
> relativo a ela), ou defina `RFID_HTTPS_KEYSTORE` com caminho absoluto. O banco fica no PostgreSQL, fora dessa pasta.

### Cadastrar o certificado nos coletores

O certificado HTTPS é próprio do servidor (gerado na primeira execução, válido por 10 anos), e cada coletor só
conversa com o servidor depois que alguém confirma, uma vez por coletor, que o certificado é o deste servidor.
Assim, outro computador na rede não consegue se passar pelo servidor para receber leituras, cadastros e senhas
dos operadores.

**1. Pegue a impressão digital SHA-256 no servidor** (32 pares, ex.: `63:0F:85:…:92:36`):

- na tela **Status** (`/status`, **só ADMIN**), no campo da impressão digital do certificado HTTPS; ou
- no **log**, que mostra a digital a cada inicialização (o caminho para quem não é ADMIN). Na pasta de onde o
  servidor roda:
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
├── java\                         ← JDK 17 do servidor (opcional; sem ela o .bat usa JAVA_RESERVA)
├── docker\                       ← docker-compose.yml e .env do banco; backups\ com os dumps do PostgreSQL
├── servico\                      ← opcional, não usado: WinSW e rfid-servidor.xml, só para instalar como serviço do Windows
└── data\                         ← criada na primeira execução (certificado HTTPS, logs, APKs, backups do certificado)
```

1. Em `config\application.properties`, coloque o nome e a chave de cada coletor (`rfid.dispositivos.chaves`) e
   os que podem cadastrar (`rfid.dispositivos.cadastro`). As outras opções vêm comentadas com o valor padrão.
   Esse arquivo faz o papel das variáveis de ambiente da tabela abaixo e **não vai para o git** (tem as chaves).
2. **Java 17:** copie um JDK 17 inteiro para a pasta `RFID_Servidor\java\` (ex.: o conteúdo de
   `C:\Program Files\Microsoft\jdk-17...`). O `.bat` e o serviço usam `java\bin\java.exe` primeiro: é o Java do
   servidor, vai junto numa migração e não muda quando o IntelliJ atualiza os JDKs dele. Sem essa pasta, o `.bat`
   usa o caminho de `JAVA_RESERVA`. Libere no Firewall do Windows o `java.exe` usado, senão os coletores não
   conectam (o `.bat` avisa quando não encontra a regra e mostra o comando que libera).
3. Dê dois cliques no `.bat`. A janela fica aberta com o servidor no ar; para parar, **Ctrl+C** (o servidor fecha o
   banco antes de sair). O `.bat` não inicia se a porta 8443 já estiver em uso (ex.: o servidor do IntelliJ ainda
   rodando), para os dois não disputarem a porta e o banco. Se o banco (porta 5433) não estiver no ar, ele desiste
   na hora; com o parâmetro **`/aguardar`** (`iniciar-servidor.bat /aguardar`), tenta de novo a cada 10 s por até
   5 min antes de desistir (útil logo depois de ligar o computador, com o Docker Desktop ainda subindo).

> **Não clique dentro da janela do `.bat`.** Com o **"Modo de Edição Rápida"** do console do Windows ligado (o
> padrão), um clique na janela começa a selecionar texto e **pausa o servidor**: as telas e os coletores ficam sem
> resposta até alguém apertar **Esc** (ou clicar com o botão direito) na janela. Prefira abrir o `.bat` pelo
> **Windows Terminal** (Windows 11: já é o padrão), ou desligue a opção: botão direito na barra de título da
> janela, **Propriedades**, desmarque **Modo de Edição Rápida**. Servidor "travado" sem nada no log? Confira se o
> título da janela começa com "Selecionar".

**Versão nova do servidor:** pare com Ctrl+C, troque `servidor\rfid-server.jar` e inicie de novo. Os dados e o
certificado ficam em `data\` e não mudam.

#### Depois de reiniciar o Windows (sem serviço)

Nesta instalação o servidor roda pela janela do `iniciar-servidor.bat`, **sem serviço do Windows**. Para ele voltar
ao ar depois de uma reinicialização (atualização do Windows, falta de energia):

1. **Docker Desktop inicia com o Windows:** em *Settings > General*, deixe ligado *Start Docker Desktop when you
   sign in to your computer*. Os contêineres do banco e do backup voltam sozinhos com ele (`restart: unless-stopped`).
2. **Suspensão desligada na tomada:** em *Configurações > Sistema > Energia* (ou *Opções de energia*), "Quando
   conectado, colocar o dispositivo em suspensão após": **Nunca**. Desligar só a tela pode. Com o computador
   suspenso, o servidor para e os coletores ficam sem conexão.
3. **Depois de reiniciar:** entre no Windows e abra o `iniciar-servidor.bat` com `/aguardar` (ele espera o Docker
   Desktop terminar de subir o banco). A janela precisa ficar aberta (minimizada serve).
   - **Opção:** para abrir sozinho quando alguém entra no Windows, crie um atalho na pasta **Inicializar**
     (`Win+R` > `shell:startup`) com o destino `"C:\RFID_Servidor\iniciar-servidor.bat" /aguardar` e *Iniciar em*
     `C:\RFID_Servidor`. Ainda depende de alguém entrar no Windows (o Docker Desktop também).
4. Confira no log (ou na janela) a linha `Servidor RFID versão ... no ar na porta 8443`.

#### Serviço do Windows (opcional, não usado nesta instalação)

> A pasta `scripts\windows\servico\` (WinSW) continua no repositório como opção, mas **não é usada**: a decisão foi
> rodar pelo `.bat` (acima). As instruções ficam aqui para referência, caso isso mude.

Com o servidor instalado como serviço ("Servidor RFID"), ele sobe sozinho ao ligar o computador, não depende de
uma janela aberta e volta sozinho se cair (nova tentativa a cada 30 s, o que também cobre o Docker ainda subindo
logo depois de ligar o computador). Usa o [WinSW](https://github.com/winsw/winsw) 2.12.0.

1. Crie a pasta `RFID_Servidor\servico\` e copie para ela os arquivos de `scripts\windows\servico\`
   (`rfid-servidor.xml`, `instalar-servico.bat` e `remover-servico.bat`).
2. Baixe o `WinSW-x64.exe` em <https://github.com/winsw/winsw/releases/tag/v2.12.0> e salve nessa pasta com o
   nome `rfid-servidor.exe` (o nome tem de ser igual ao do `.xml`). Ele não vai para o git.
3. O serviço usa o Java da pasta `RFID_Servidor\java\` (`executable` no `rfid-servidor.xml`), liberado no
   Firewall. O `instalar-servico.bat` recusa instalar se ele não existir. Para usar outro Java, troque o caminho
   em `executable`.
4. Pare o servidor aberto pelo `.bat` (Ctrl+C na janela dele).
5. Clique com o botão direito no `instalar-servico.bat` > **Executar como administrador**.

Depois disso, **não use mais o `iniciar-servidor.bat`** (ele recusa iniciar com a porta 8443 em uso). Parar e
iniciar: `services.msc` > "Servidor RFID", ou, como administrador, `rfid-servidor.exe stop` / `start` na pasta
`servico`. O serviço para o servidor com Ctrl+C (o banco é fechado antes de sair). O log continua em
`data\logs\rfid-server.log`; a saída do console fica em `servico\logs`.

**Versão nova do servidor com o serviço:** pare o serviço, troque `servidor\rfid-server.jar` e inicie de novo.

**Voltar para o `.bat`:** botão direito no `remover-servico.bat` > **Executar como administrador**.

> O banco roda no Docker Desktop, que só sobe depois que alguém entra no Windows. Até lá o serviço fica tentando
> de novo a cada 30 s e o servidor entra no ar sozinho assim que o banco estiver pronto.

> Já tinha um servidor rodando pelo IntelliJ? O certificado HTTPS dele fica em `data\` dentro da pasta de trabalho
> da configuração de execução do IntelliJ. Copie a `data\` inteira (com o servidor parado) para a pasta nova: assim
> o certificado é o mesmo e os coletores não precisam ser configurados de novo. O banco não fica mais em `data\`
> (é o PostgreSQL no Docker): para levá-lo, siga o [passo a passo da migração](docs/migracao-servidor.md).

---

## Configuração (variáveis de ambiente)

Todas têm padrão no `application.yml`; normalmente só as de coletores precisam ser definidas.

| Variável | Padrão | Para quê |
|---|---|---|
| `RFID_CHAVES_DISPOSITIVOS` | *(vazio = nenhum coletor entra)* | `nome:chave` dos coletores, separados por vírgula |
| `RFID_COLETORES_CADASTRO` | *(vazio)* | Coletores que podem cadastrar etiquetas |
| `RFID_COLETORES_DIAS_SEM_CONTATO` | `7` | Dias sem contato até o coletor aparecer destacado na tela Coletores |
| `RFID_ENDERECO_QR` | *(vazio)* | Endereço do servidor no QR de configuração dos coletores (ex.: `172.16.242.22` ou um nome). Vazio = o da tela ou o IP da rede local |
| `RFID_APP_CERTIFICADO_SHA256` | *(o da chave da empresa)* | Impressão digital do certificado da chave do app: só APK assinado com ela é publicado (vazio = publicação desligada) |
| `RFID_APP_PASTA` | `./data/app` | Pasta dos APKs publicados (fora do backup automático) |
| `RFID_APP_PACOTE` | `br.com.bkpbr.rfidandroid` | Pacote do app dos coletores: APK de outro pacote é recusado |
| `RFID_APP_VERSOES_GUARDADAS` | `3` | Quantas versões mantêm o arquivo no disco |
| `RFID_COLETORES_ERROS_POR_DIA` | `500` | Erros do app aceitos por coletor por dia (acima disso são descartados) |
| `RFID_COLETORES_DIAS_ERROS` | `90` | Por quantos dias os erros dos coletores ficam guardados |
| `RFID_DB_URL` | `jdbc:postgresql://localhost:5433/rfid` | Endereço do banco |
| `RFID_DB_POOL` | `30` | Conexões com o banco ao mesmo tempo (o PostgreSQL aceita 100) |
| `RFID_DB_USER` / `RFID_DB_PASSWORD` | `rfid` / *(vazio)* | Usuário e senha do banco (a mesma do `docker/.env`) |
| `RFID_HTTPS_HABILITADO` | `true` | Liga o HTTPS |
| `RFID_HTTPS_PORTA` | `8443` | Porta HTTPS (a HTTP é 8082) |
| `RFID_HTTPS_KEYSTORE` | `./data/rfid-https.p12` | Certificado (use o da empresa, se houver) |
| `RFID_HTTPS_SENHA` | *(gerada e salva em `.p12.senha`)* | Senha do certificado |
| `RFID_HTTPS_NOMES` | *(vazio)* | Nomes/IPs extras do certificado gerado (só na geração) |
| `RFID_DURACAO_SESSAO_OPERADOR` | `12h` | Duração do login no coletor |
| `RFID_JANELA_SINCRONIZACAO` | `72h` | Tempo máximo que um coletor pode ficar offline sem perder ações |
| `RFID_LEITURAS_POR_PAGINA` | `50` | Leituras por página na tela de leituras (10 a 200) |
| `RFID_LEITURAS_TETO_CONTAGEM` | `10000` | Até quantas leituras a tela conta; acima disso mostra "mais de N" (contar milhões a cada página é lento) |
| `RFID_LEITURAS_RETER_DIAS` | `0` (desligada) | Apaga leituras com mais de N dias, 10 min depois de subir e a cada 24 h (mínimo 30; não afeta inventários) |
| `RFID_BACKUP_ANTES_MIGRACAO` | `docker exec rfid-backup /backup.sh` | Backup do banco antes de aplicar migrações (vazio = migra sem backup) |
| `RFID_BANCO_PASTA_BACKUPS` | `./docker/backups` | Pasta dos dumps do contêiner de backup (a mesma do `RFID_BACKUP_PASTA` do `docker\.env`), para a tela Status conferir |
| `RFID_BANCO_BACKUP_ATRASADO_HORAS` | `12` | Horas sem backup do banco até a tela Status e o log avisarem |
| `RFID_SENHA_TAMANHO_MINIMO` | `8` | Tamanho mínimo de senha |
| `RFID_LOGIN_MAX_TENTATIVAS` | `5` | Erros por email, da mesma origem, até bloquear aquele login só para essa origem |
| `RFID_LOGIN_MAX_TENTATIVAS_EMAIL` | `20` | Erros por email somando todas as origens até bloquear para todas |
| `RFID_LOGIN_MAX_TENTATIVAS_IP` | `20` | Erros por origem até bloquear (IP na web, coletor + IP no coletor) |
| `RFID_LOGIN_BLOQUEIO_MINUTOS` | `15` | Tempo de bloqueio |
| `RFID_BACKUP_HABILITADO` | `true` | Backup automático do certificado HTTPS (o do banco é no `docker/.env`) |
| `RFID_CERT_BACKUP_PASTA` | `./data/backups` | Pasta dos backups do certificado (`RFID_BACKUP_PASTA` é a dos dumps do banco, no `docker\.env`) |
| `RFID_BACKUP_SENHA` | (vazio) | Senha que **cifra** o backup do certificado (`.zip.cifrado`) e a cópia do banco fora do computador (`.dump.cifrado`); no `config\application.properties`, `rfid.backup.senha`. Guarde-a fora desta máquina: sem ela o backup não restaura. Vazio: o `.zip` sai aberto, com a chave privada, e a cópia do banco não é feita |
| `RFID_BANCO_COPIA_EXTERNA` | (vazio) | Pasta da cópia cifrada do banco fora do computador (ex.: `${OneDriveCommercial}/Backups RFID/banco`). Vazio = desligada |
| `RFID_BANCO_COPIA_MANTER` | `14` | Quantas cópias do banco guardar na pasta externa |
| `RFID_SMTP_HOST`, `RFID_SMTP_PORTA`, `RFID_SMTP_USUARIO`, `RFID_SMTP_SENHA` | (vazio), `587` | Servidor de email dos avisos (ex.: `smtp.office365.com`). Vazio = avisos só no log |
| `RFID_ALERTAS_EMAIL` | (vazio) | Quem recebe os avisos por email (separados por vírgula) |
| `RFID_ALERTAS_DE` | (o usuário do SMTP) | Remetente dos avisos |
| `RFID_MINUTOS_FORA` | `30` | Minutos fora do ar (ou parado) a partir dos quais o servidor avisa quando volta |
| `RFID_BACKUP_CRON` | `0 0 2 * * *` | Horário (todo dia às 02:00) |
| `RFID_SESSAO_WEB_TIMEOUT` | `30m` | Tempo sem uso até a sessão das telas acabar (ex.: `45m`) |
| `RFID_WEB_MAX_CORPO_BYTES` | `1048576` | Tamanho máximo do corpo das requisições das telas que não são upload (1 MB) |
| `RFID_ANEXOS_MAX_BYTES` | `10485760` | Tamanho máximo de cada foto ou anexo do bem (10 MB) |
| `RFID_ANEXOS_MAX_POR_BEM` | `20` | Quantos anexos cada bem pode ter |
| `RFID_BACKUP_MANTER` | `14` | Quantos backups do certificado guardar |
| `RFID_LOG_ARQUIVO` | `./data/logs/rfid-server.log` | Arquivo de log (vazio = só console); um por dia, guarda 30 dias |
| `RFID_MOSTRAR_SQL` | `false` | Mostra no log cada SQL executado (só para investigar problema) |

---

## Desenvolvimento e testes

```bash
./gradlew test        # ~1 min; relatório em build/reports/tests/test/index.html
./gradlew bootRun     # sobe o servidor
```

Se aparecer `JAVA_HOME is not set`, aponte para um JDK 17.

**CI:** em pull requests e a cada push na `master` (commit só de documentação não roda), o GitHub Actions roda
`./gradlew test` no Linux e, no Windows, os testes que não sobem banco (`./gradlew test -PsemDocker`) e o
`iniciar-servidor.bat` de verdade (`scripts/windows/testar-iniciar-servidor.ps1`; ele também roda na máquina, com
`-PortaBanco` numa porta livre se o banco estiver no ar) (`.github/workflows/testes.yml`). O resultado aparece na aba **Actions** do repositório; se algum teste falhar,
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
  integracao/                              testes com a aplicação completa (MockMvc + PostgreSQL no Testcontainers)
  suporte/                                 base dos testes de integração e usuários simulados
```

Convenções:

- **Logs em português** e nunca com senha, token ou chave.
- Toda rota em `/admin/**` precisa de `@PreAuthorize` (a cadeia de segurança só exige login ali).
- Erros de validação: lance `IllegalArgumentException` (vira 400 com a mensagem); conflito de estado:
  `IllegalStateException` (409). O tratamento global devolve JSON nas APIs e a página de erro nas telas.
- Os testes usam um **PostgreSQL de verdade num contêiner temporário** (Testcontainers): o Docker precisa estar
  ligado para rodar `./gradlew test` (no GitHub Actions ele já está).
- Testes de integração estendem `suporte/IntegracaoBase` (perfil `test`, transação desfeita a cada teste); use senhas que passam na política (ex.: `Kx9mPq2v`) e email/IP únicos em testes de login.

---

## Logs

Tudo em português. Sai no console e no arquivo `./data/logs/rfid-server.log` (mude com `RFID_LOG_ARQUIVO`):
um arquivo por dia (ou a cada 10 MB), guardando os últimos 30 dias. Exemplos do que aparece:

```
Servidor RFID versão 1.4.3 no ar na porta 8443 (iniciado em 13/10/2026 11:17, pronto em 9,8 s)
Banco de dados: PostgreSQL (localhost:5433/rfid)
Backup do certificado HTTPS concluído (agendado): rfid-certificado-2026-10-13_020000.zip (rfid-https.p12, rfid-https.p12.senha)
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
| Coletor diz que o certificado não confere | A impressão digital no app difere da do servidor (log ou `/status`, só ADMIN) (certificado foi trocado ou restaurado de outro servidor). Confirme de novo em cada coletor, conferindo a digital nova: ver [Cadastrar o certificado nos coletores](#cadastrar-o-certificado-nos-coletores). |
| "Muitas tentativas sem sucesso" | Bloqueio de login: espere o tempo indicado (ou reinicie o servidor, que zera os contadores). |
| Etiqueta lida não aparece nas leituras | Cadastro incompleto (falta tipo, marca, modelo, patrimônio ou nº de série). |
| Servidor não sobe: certificado não abre | O `.p12` ou o `.senha` foi alterado/perdido. Restaure os dois do backup — não apague, senão um novo certificado é gerado e os coletores precisam ser pareados de novo. |
| Não consigo excluir um modelo/marca/tipo | Ele está em uso por etiquetas (a mensagem diz quantas). |
| Servidor não sobe: `Connection to localhost:5433 refused` | O banco não está no ar: abra o Docker Desktop e, na pasta `docker`, `docker compose up -d` (`docker compose ps` mostra a situação). Logo depois de ligar o computador, use `iniciar-servidor.bat /aguardar`. |
| Telas e coletores sem resposta, sem erro no log, e a janela do `.bat` com título "Selecionar…" | Alguém clicou na janela com o "Modo de Edição Rápida" ligado: o console pausou o servidor. Aperte **Esc** na janela; para não repetir, use o Windows Terminal ou desligue a Edição Rápida (ver [Rodar no Windows fora do IntelliJ](#rodar-no-windows-fora-do-intellij)). |
| Servidor não sobe: `password authentication failed` | A senha em `config\application.properties` (ou `RFID_DB_PASSWORD`) é diferente da do `docker/.env`. |
| Certificado sumiu depois de mover o servidor de pasta | O certificado é relativo à pasta de execução (`./data`): use a pasta original ou defina `RFID_HTTPS_KEYSTORE`. |
