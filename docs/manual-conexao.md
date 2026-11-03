# Manual: como instalar o servidor e conectar os coletores

Passo a passo para colocar o Sistema RFID no ar e ligar um coletor Android a ele. Para os detalhes técnicos
(API, variáveis, segurança), veja o [README do servidor](../README.md). Se algo der errado, veja
[Principais erros e como resolver](erros-comuns.md).

```
 Coletor (app RFID Android) ──HTTPS 8443──►  Servidor RFID  ──►  PostgreSQL (Docker)
 Navegador (equipe)        ──HTTPS 8443──►
```

## Sumário

1. [O que você precisa](#1-o-que-você-precisa)
2. [Instalar o servidor](#2-instalar-o-servidor)
3. [Primeiro acesso pela web](#3-primeiro-acesso-pela-web)
4. [Cadastrar o coletor no servidor](#4-cadastrar-o-coletor-no-servidor)
5. [Instalar o app no coletor](#5-instalar-o-app-no-coletor)
6. [Configurar o coletor](#6-configurar-o-coletor)
7. [Entrar no coletor (operador)](#7-entrar-no-coletor-operador)
8. [Conferir que o coletor está conectado](#8-conferir-que-o-coletor-está-conectado)
9. [Mudar o servidor de um coletor já configurado (PIN)](#9-mudar-o-servidor-de-um-coletor-já-configurado-pin)
10. [Atualizar o servidor e o app](#10-atualizar-o-servidor-e-o-app)

---

## 1. O que você precisa

| Item | Onde conseguir |
|---|---|
| Computador Windows para o servidor, sempre ligado e na mesma rede dos coletores | — |
| **Java 17** | ex.: Microsoft Build of OpenJDK 17 |
| **Docker Desktop** (para o banco PostgreSQL) | docker.com |
| `rfid-server.jar`, `iniciar-servidor.bat` e `application.properties.exemplo` | [Releases do RFID-Server](https://github.com/RaphaelNDS/RFID-Server/releases) |
| `docker-compose.yml` e `.env.exemplo` | pasta [`docker/`](../docker) deste repositório |
| APK do app (`RFID-Android-<versão>-release.apk`) | [Releases do RFID-SERVER-ANDROID](https://github.com/RaphaelNDS/RFID-SERVER-ANDROID/releases) |
| Coletor Zebra com Android 8.0 ou mais novo | — |

Anote o **IP do computador do servidor** na rede (no Windows: `ipconfig`, linha "Endereço IPv4"). Os coletores
vão usar esse IP. Dê preferência a um IP fixo (reservado no roteador/DHCP): se o IP mudar, todos os coletores
precisam ser configurados de novo.

---

## 2. Instalar o servidor

### 2.1 Banco de dados

1. Crie uma pasta `docker` e coloque nela o `docker-compose.yml` e o `.env.exemplo`.
2. Copie `.env.exemplo` para `.env` e **troque a senha** do banco.
3. Com o Docker Desktop aberto, na pasta `docker`:
   ```powershell
   docker compose up -d
   docker compose ps      # rfid-postgres e rfid-backup devem aparecer como "running"
   ```
4. No Docker Desktop, ligue *Start Docker Desktop when you sign in*, para o banco voltar junto com o Windows.

### 2.2 Pasta do servidor

Monte a pasta assim (ex.: `C:\RFID_Servidor`):

```
RFID_Servidor\
├── iniciar-servidor.bat
├── servidor\rfid-server.jar
├── config\application.properties   ← cópia do application.properties.exemplo, preenchida
└── data\                           ← criada na primeira execução (certificado, logs, backups, APKs)
```

Em `config\application.properties`:

```properties
# A mesma senha do docker\.env
spring.datasource.password=<senha do banco>
# Nome e chave de cada coletor (ver o passo 4)
rfid.dispositivos.chaves=coletor-01:<chave longa do coletor-01>
# Coletores que podem cadastrar etiquetas (opcional)
rfid.dispositivos.cadastro=coletor-01
```

> Esse arquivo tem a senha do banco e as chaves dos coletores: **não mande por email nem coloque no git**.

### 2.3 Iniciar

1. No `iniciar-servidor.bat`, confira a linha `set "JAVA=..."` com o caminho do `java.exe` 17.
2. Dê dois cliques no `.bat`. **A janela precisa ficar aberta**: fechar a janela desliga o servidor.
3. Quando aparecer no log `Servidor RFID versão ... no ar na porta 8443`, o servidor está pronto.
4. Na primeira vez, o **Firewall do Windows** pergunta se libera o Java: **permita em redes privadas**. Sem isso os
   coletores não conectam (o navegador no próprio servidor funciona, o que engana).

Para o servidor subir sozinho ao ligar o computador (e não cair ao fechar a janela), instale-o como serviço do
Windows com o `instalar-servico.bat`. Veja "Serviço do Windows" no [README](../README.md#serviço-do-windows).

---

## 3. Primeiro acesso pela web

1. Abra `https://<IP do servidor>:8443` no navegador. O navegador avisa do certificado próprio: aceite a exceção
   (é esperado; os coletores conferem o certificado de outro jeito, no passo 6).
2. Na primeira vez abre a **configuração inicial**: crie o usuário **administrador**.
3. Cadastre o **catálogo** (tipos → marcas → modelos) e os **usuários** dos operadores (tela Usuários). O operador
   precisa de pelo menos **Leituras · Ver** para usar o coletor; para cadastrar etiquetas pelo coletor, também
   **RFID · Criar**.
4. Anote a **impressão digital SHA-256 do certificado** do servidor, na tela **Status** (`/status`). Ela é usada no
   passo 6. Também aparece no log:
   ```powershell
   Select-String "SHA-256 do certificado HTTPS" .\data\logs\rfid-server.log | Select-Object -Last 1
   ```

---

## 4. Cadastrar o coletor no servidor

Cada coletor tem um **nome** e uma **chave** (uma senha só dele, com 16 caracteres ou mais).

1. Escolha o nome (ex.: `coletor-01`) e gere uma chave longa e aleatória.
2. Inclua em `config\application.properties`, separando os coletores por vírgula:
   ```properties
   rfid.dispositivos.chaves=coletor-01:<chave-1>,coletor-02:<chave-2>
   ```
3. Se o coletor vai cadastrar etiquetas, inclua o nome em `rfid.dispositivos.cadastro`.
4. **Reinicie o servidor** (Ctrl+C na janela e dois cliques no `.bat` de novo): as chaves só são lidas ao iniciar.
5. Na web, a tela **Coletores** (somente ADMIN) passa a mostrar o coletor como "nunca conectou".

Entregue a chave a quem vai configurar o coletor por um canal seguro (pessoalmente, cofre de senhas).

---

## 5. Instalar o app no coletor

1. Baixe o APK da última release do app e copie para o coletor (cabo USB, pasta de rede ou download direto).
2. Abra o APK no coletor. O Android pede para **permitir a instalação desta fonte**: permita e instale.
3. Se o coletor já tinha uma versão de **desenvolvimento** do app (instalada pelo Android Studio), o Android não
   instala por cima. Antes de desinstalar a antiga, confira na tela principal que não há nada **"aguardando
   envio"**: desinstalar apaga o que está guardado no coletor.

Depois da primeira instalação, as próximas versões chegam pelo próprio servidor (passo 10).

---

## 6. Configurar o coletor

1. Abra o app. Na tela de login, toque em **Configurações do coletor**.
2. **Endereço do servidor:** o IP do servidor (ex.: `192.168.0.10`). O app usa sempre HTTPS na porta 8443;
   `192.168.0.10:8443` também funciona. Não use `http://` nem a porta 8082.
3. **Chave do coletor:** a chave do passo 4.
4. Toque em **Salvar**.
5. **Confirmar o servidor:** o app mostra a impressão digital do certificado em 4 linhas de 8 pares. Compare
   **todos os pares** com a da tela Status do servidor (passo 3). Iguais: **"É igual, confiar"**. Qualquer
   diferença: **"Não confiar"** e avise o responsável pelo servidor.
6. **Criar o PIN do coletor:** na primeira vez o app pede um PIN de **4 a 8 números** (sem sequência como
   `1234` e sem repetir o mesmo número, como `1111`). O PIN protege o endereço do servidor, a chave e o
   certificado deste coletor. **Guarde-o com o responsável pelos coletores.**
7. A tela deve mostrar **"Servidor: conectado com segurança (HTTPS) ✅"**.

Confira também, nas configurações do Android, **data, hora e fuso automáticos** (ex.: America/Sao_Paulo). Um
coletor em GMT mostra os horários 3 horas adiantados (os dados vão certos ao servidor, só a tela fica errada).

---

## 7. Entrar no coletor (operador)

1. Na tela de login do app, o operador entra com o **email e a senha do usuário dele** no Sistema RFID.
2. A sessão vale **12 horas**. Entrar exige rede; depois o app funciona **mesmo sem rede** e envia tudo quando a
   conexão volta.
3. **Sair** encerra a sessão. O próximo operador entra com o próprio usuário.

Esqueceu a senha? Toque em **Esqueceu a senha?** na tela de login: o pedido vai para o administrador, que gera
uma senha temporária na tela Usuários. A senha temporária só vale na web: troque-a lá antes de entrar no coletor.

---

## 8. Conferir que o coletor está conectado

- **No coletor:** abra **Configurações**. A linha "Servidor: conectado com segurança (HTTPS) ✅" confirma a conexão.
  Abrir essa tela também faz o coletor avisar o servidor.
- **Na web:** a tela **Coletores** mostra o último contato, o IP, a versão do app e o operador de cada coletor.
- **No log do servidor** (`data\logs\rfid-server.log`): `Ping recebido do dispositivo coletor-01`.

> O coletor só fala com o servidor quando tem algo a fazer (login, leitura, envio do que estava na fila, procura
> de atualização ou ao abrir Configurações). Coletor parado não aparece no log, e isso não é erro.

---

## 9. Mudar o servidor de um coletor já configurado (PIN)

Trocar o **endereço do servidor** ou a **chave** de um coletor exige o PIN do coletor:

1. Em **Configurações**, altere o endereço ou a chave e toque em **Salvar**.
2. Aparece **"PIN do coletor"**: digite o PIN e toque em **Confirmar**.

O PIN só é pedido quando o endereço ou a chave **mudam**. Salvar sem mudar nada só mostra "Nada mudou nas
Configurações".

**Esqueceu o PIN?** Na janela "PIN do coletor", toque em **Esqueci o PIN** e peça a um **administrador** do Sistema
RFID para digitar o email e a senha dele ali mesmo. O servidor confere se é mesmo um ADMIN; se for, o PIN antigo é
apagado e o app pede para criar um novo. Isso não encerra a sessão do operador que está usando o coletor.

Errar o PIN **5 vezes seguidas** bloqueia novas tentativas por **5 minutos** (o "Esqueci o PIN" continua disponível).

---

## 10. Atualizar o servidor e o app

### Servidor

1. Baixe o `rfid-server.jar` da release nova.
2. Pare o servidor (**Ctrl+C** na janela do `.bat`).
3. Guarde o jar atual com outro nome (ex.: `rfid-server-anterior.jar`), para poder voltar.
4. Coloque o novo em `servidor\rfid-server.jar` e inicie o `.bat` de novo.

O banco, o certificado e as configurações não mudam: os coletores continuam conectados sem configurar nada.
Mudanças no banco são aplicadas sozinhas ao iniciar (o log mostra as migrações).

### App dos coletores

1. Baixe o APK da release nova.
2. Na web, entre como ADMIN e abra **App** no menu (`/admin/app`). Envie o APK com as notas da versão.
3. Os coletores oferecem a atualização sozinhos na tela de login (no máximo a cada 30 min) ou em
   **Configurações → Procurar atualização do app**. O operador confirma a instalação.

O servidor só aceita APK assinado com a chave da empresa e com código de versão maior que o já publicado. O que
está guardado no coletor (fila de envio, configurações, PIN) continua depois da atualização.
