# Principais erros e como resolver

Organizado pelo que aparece na tela. As mensagens estão como o sistema mostra. Para instalar e conectar do zero,
veja o [Manual de conexão](manual-conexao.md).

**Antes de tudo, onde olhar:**

- **Coletor:** tela **Configurações**, linha "Servidor: ...". Ela testa a conexão sempre que a tela abre.
- **Servidor:** o arquivo `data\logs\rfid-server.log`, na pasta do servidor (tudo em português). Para ver o fim:
  ```powershell
  Get-Content .\data\logs\rfid-server.log -Tail 30
  ```
- **Web:** a tela **Coletores** (último contato, IP, versão do app e erros enviados por cada coletor) e a tela
  **Status** (versão do servidor e impressão digital do certificado).

## Sumário

1. [Coletor não conecta ao servidor](#1-coletor-não-conecta-ao-servidor)
2. [Certificado do servidor](#2-certificado-do-servidor)
3. [Login no coletor](#3-login-no-coletor)
4. [PIN das Configurações](#4-pin-das-configurações)
5. [Leituras, envio e cadastro](#5-leituras-envio-e-cadastro)
6. [Atualização do app](#6-atualização-do-app)
7. [Servidor e web](#7-servidor-e-web)

---

## 1. Coletor não conecta ao servidor

| No coletor aparece | Causa provável | O que fazer |
|---|---|---|
| **Servidor: sem conexão ❌** / "Sem conexão com o servidor" | O servidor está desligado, a janela do `iniciar-servidor.bat` foi fechada, o IP está errado, o coletor está fora da rede ou o Firewall do Windows bloqueia o Java | 1) No servidor, confira se a janela do `.bat` está aberta e se o log mostra "no ar na porta 8443". 2) Abra `https://<IP>:8443` no navegador **de outro computador ou do celular** na mesma rede: se não abrir, é rede ou firewall. 3) Confira o IP do servidor com `ipconfig` (pode ter mudado). 4) No Firewall do Windows, libere a entrada para o `java.exe` usado no `.bat`. 5) Confira o Wi-Fi do coletor. |
| **Servidor: chave do coletor inválida ou não configurada ❌** / "Chave do coletor inválida: verifique as Configurações" | A chave do app não é a mesma do servidor, ou o coletor não está em `rfid.dispositivos.chaves` | Confira a chave no `config\application.properties` e no app (sem espaços a mais). Mudou o arquivo? **Reinicie o servidor.** O log do servidor mostra ao iniciar "Chaves de dispositivo carregadas para N coletor(es)". |
| **Servidor: endereço do servidor inválido ❌** / "Endereço inválido. Use o IP (ex.: 192.168.0.10) ou IP:porta (ex.: 192.168.0.10:8443)" | Foi digitado `http://`, `https://`, barra ou espaço | Digite só o IP, ou IP:porta. |
| "Servidor: não configurado. Informe o IP do servidor e a chave do coletor e toque em Salvar" | Coletor novo, ainda sem servidor | Siga o passo 6 do [manual](manual-conexao.md#6-configurar-o-coletor). |
| "Salvo, mas sem a chave do coletor o servidor recusa as chamadas" | Salvou só o IP | Informe a chave e salve de novo. |
| **Servidor: resposta inesperada (servidor desatualizado?) ❌** / "Servidor desatualizado (HTTP 404)" | O servidor é de uma versão mais antiga que o app, ou o endereço aponta para outro programa | Atualize o servidor (passo 10 do manual). Confira se o IP é mesmo o do servidor RFID. |
| Erro dizendo para usar HTTPS (**426**) | O app está chamando a porta HTTP 8082 | Use o IP sem porta ou com `:8443`. |
| Erro **410** / no log do servidor: "Rota antiga dos coletores chamada" | App muito antigo no coletor | Instale o APK atual (passo 5 do manual). O log mostra o IP do coletor. |
| Tela **Coletores** mostra "nunca conectou" | A chave existe no servidor, mas o coletor nunca fez uma chamada aceita | Confira a chave e o endereço nas Configurações do app e abra a tela Configurações no coletor. |
| O servidor está no ar, mas o log não mostra nada do coletor | Normal com o coletor parado: ele só chama o servidor quando tem algo a fazer | Abra **Configurações** no coletor: deve aparecer no log `Ping recebido do dispositivo <nome>`. |

---

## 2. Certificado do servidor

| No coletor aparece | Causa provável | O que fazer |
|---|---|---|
| **"Confirmar o servidor"** com a impressão digital | Primeira conexão deste coletor com este servidor (esperado) | Compare **todos os pares** com a impressão digital da tela **Status** do servidor e toque em "É igual, confiar" só se forem iguais. |
| **Servidor: certificado do servidor ainda não confirmado ❌** / "Certificado do servidor ainda não confirmado: abra as Configurações do coletor" | Alguém tocou em "Não confiar" ou fechou a confirmação | Abra Configurações, toque em Salvar (ou Testar conexão) e confirme como acima. |
| **"Certificado do servidor mudou"** / **Servidor: certificado do servidor DIFERENTE do confirmado ❌** | O certificado do servidor foi trocado: servidor novo, pasta `data` perdida ou movida, backup de outro servidor restaurado. **Ou alguém se passando pelo servidor na rede** | Se o certificado **não** foi trocado de propósito, **não confie** e avise o responsável. Se foi, compare a digital **nova** com a da tela Status e confirme. Confirmar exige o PIN do coletor. |
| Certificado mudou depois de mover o servidor de pasta | O certificado fica em `data\rfid-https.p12` e `data\rfid-https.p12.senha`, na pasta de onde o servidor roda | Volte o servidor para a pasta original, ou copie a pasta `data` inteira (com o servidor parado). Assim os coletores não precisam confirmar de novo. |

---

## 3. Login no coletor

| No coletor aparece | Causa provável | O que fazer |
|---|---|---|
| "Email ou senha incorretos" | Email ou senha errados (o servidor não diz qual dos dois) | Confira o email. Se esqueceu a senha, use **Esqueceu a senha?**. |
| "Muitas tentativas sem sucesso. Aguarde N minuto(s) e tente de novo." | 5 senhas erradas no mesmo email (web e coletor somados) em 15 min | Espere o tempo indicado; nem a senha certa entra antes disso. O ADMIN pode gerar uma senha temporária, que também libera o login. |
| "Você está com uma senha temporária: entre pela web, crie sua senha nova e depois entre no coletor com ela." | O ADMIN gerou uma senha temporária | Entre pela web com ela, crie a senha nova e use a nova no coletor. |
| "Seu usuário não tem permissão para usar o coletor (é preciso acesso a Leituras). Fale com o administrador." | O usuário não tem **Leituras · Ver** | Na web, em **Usuários**, dê a permissão (ou um perfil que a tenha). |
| "Seu usuário foi desativado. Fale com o administrador." | O usuário foi desativado em **Usuários** | O ADMIN reativa o usuário. |
| "Sua senha não atende mais às regras de segurança: troque-a no Sistema RFID (web)." | A senha é antiga e não segue a regra atual | Troque a senha pela web (tela Meu perfil). |
| Volta sozinho para a tela de login | A sessão de 12 h acabou | Entre de novo. O que estava na fila continua e é enviado. O app avisa com 30 e 5 minutos de antecedência e deixa renovar sem sair da tela. |

---

## 4. PIN das Configurações

| No coletor aparece | Causa provável | O que fazer |
|---|---|---|
| Mudei um campo, toquei em Salvar e **o PIN não foi pedido** / "Nada mudou nas Configurações" | O PIN só é pedido quando o **endereço do servidor** ou a **chave** mudam | Normal. Para testar o PIN, altere o endereço (ex.: `IP` → `IP:8443`, que é o mesmo servidor) e toque em Cancelar no fim. |
| "PIN incorreto. Restam N tentativa(s)" | PIN errado | Tente de novo ou use **Esqueci o PIN**. |
| **"PIN bloqueado"** / "Muitas tentativas erradas. Aguarde N minuto(s) ou peça a um administrador para liberar o PIN." | 5 PINs errados seguidos | Espere 5 minutos ou use **Esqueci o PIN**. |
| "PIN fácil demais: não use números em sequência" / "...não use o mesmo número repetido" | PIN como `1234` ou `1111` | Escolha outro PIN de 4 a 8 números. |
| "Os dois PINs não são iguais" | O PIN e a confirmação estão diferentes | Digite os dois de novo. |
| No "Esqueci o PIN": **"Só um administrador pode liberar o PIN das Configurações do coletor."** | O email informado não é de um usuário **ADMIN** | Peça a um administrador para digitar o email e a senha dele. Confira o perfil na tela **Usuários**. |
| No "Esqueci o PIN": "Email ou senha incorretos" / "Muitas tentativas sem sucesso..." | Senha errada do administrador; as tentativas contam como as do login | Confira a senha; se bloqueou, espere o tempo indicado. |
| "Servidor desatualizado: atualize o servidor para liberar o PIN" | Servidor de uma versão sem o "Esqueci o PIN" | Atualize o servidor. |

> Mudou o perfil de um usuário em **Usuários** e nada mudou? Confira se apareceu a mensagem verde "Usuário
> alterado". Um administrador **não pode tirar o próprio acesso de ADMIN**: entre com outro administrador para fazer
> isso. Toda alteração salva fica no log do servidor ("Usuário ... alterado por ...: perfil ... → ...").

---

## 5. Leituras, envio e cadastro

| No coletor aparece | Causa provável | O que fazer |
|---|---|---|
| Faixa laranja "aguardando envio" que não some | Sem conexão com o servidor | Veja a [seção 1](#1-coletor-não-conecta-ao-servidor). Nada se perde: o app envia quando a conexão voltar. Itens recusados ficam marcados e não travam o resto. |
| Leituras antigas recusadas | O coletor ficou sem rede mais que o prazo de sincronização (72 h, padrão) | Conecte os coletores à rede pelo menos a cada 3 dias. |
| Etiqueta lida não aparece em **Leituras** na web | O cadastro da etiqueta está **incompleto** (falta código interno, tipo, marca, modelo, patrimônio ou nº de série) | Complete o cadastro na web (Etiquetas cadastradas → Editar). |
| "Este coletor não tem permissão para cadastrar etiquetas. Peça ao administrador para liberá-lo no servidor." | O coletor não está em `rfid.dispositivos.cadastro` | Inclua o nome do coletor e reinicie o servidor. |
| "Seu usuário não tem permissão para cadastrar etiquetas. Peça ao administrador a permissão de cadastro." | O operador não tem **RFID · Criar** | Dê a permissão em **Usuários**. |
| "Catálogo ainda não baixado. Conecte o coletor ao servidor uma vez para poder cadastrar." | Coletor novo que ainda não falou com o servidor | Conecte à rede e abra o app uma vez com login. |
| "O servidor recusou este cadastro: ... Corrija e salve de novo." | Ex.: etiqueta já cadastrada, marca de outro tipo | Corrija conforme a mensagem; o item fica na aba **Não cadastradas**. |
| Leitor não lê / "Nenhum leitor RFID encontrado" / "Leitor RFID não conectou" | Leitor Zebra desconectado, sem bateria ou mal encaixado | Confira bateria e encaixe. A leitura só acontece com o **gatilho** e na tela que está aberta. O app reconecta sozinho. |
| Horários na tela 3 horas adiantados | Coletor em GMT | Ajuste o fuso do Android (America/Sao_Paulo, data e hora automáticas). Os dados no servidor estão certos. |

---

## 6. Atualização do app

| Onde aparece | Causa provável | O que fazer |
|---|---|---|
| Coletor não oferece a atualização | Ele procura no máximo a cada 30 min na tela de login; ou o código da versão publicada não é maior que o instalado | Em **Configurações → Procurar atualização do app**. Confira na tela **App** (web) a versão publicada. |
| "App já está atualizado" | O coletor já tem a versão publicada | Nada a fazer. |
| "Nenhuma versão do app publicada no servidor" | Nada publicado ainda | Publique o APK na tela **App** (web, ADMIN). |
| Ao publicar, o servidor recusa o APK | O APK não é o de produção (não está assinado com a chave da empresa) ou o código de versão não é maior que o já publicado | Use o APK da release (`RFID-Android-<versão>-release.apk`), nunca o de desenvolvimento. |
| Android pede **"Permitir desta fonte"** | Primeira atualização pelo app | Permita (uma vez só). |
| Android diz que o app **não foi instalado** / pacote conflitante | O coletor tem o app de **desenvolvimento**, assinado com outra chave | Confira que não há nada aguardando envio, desinstale o app antigo e instale o APK da release. |
| Link **App** não aparece no menu da web | O usuário logado não é ADMIN | Entre com um administrador. |

---

## 7. Servidor e web

| Onde aparece | Causa provável | O que fazer |
|---|---|---|
| O servidor sai do ar sozinho | A janela do `iniciar-servidor.bat` foi fechada, o computador reiniciou ou dormiu | Inicie o `.bat` de novo. Para não depender da janela, registre como serviço do Windows (README, "Serviço do Windows") e desligue a suspensão do computador. |
| `.bat` diz que a porta 8443 já está em uso | Já tem um servidor rodando (outra janela ou o IntelliJ) | Pare o outro antes. Os dois não podem rodar juntos. |
| Servidor não sobe: `Connection to localhost:5433 refused` | O banco não está no ar | Abra o Docker Desktop e, na pasta `docker`, `docker compose up -d`. |
| Servidor não sobe: `password authentication failed` | A senha do `config\application.properties` é diferente da do `docker\.env` | Deixe as duas iguais. |
| Servidor não sobe: certificado não abre | `data\rfid-https.p12` ou `.senha` foi alterado ou perdido | Restaure os dois do backup (`data\backups`). **Não apague**: um certificado novo obriga a confirmar de novo em todos os coletores. |
| Navegador avisa que o certificado não é confiável | Certificado próprio do servidor | Esperado: aceite a exceção uma vez. |
| Usuário relata erro com **código** (ex.: `cde75674`) | Erro inesperado no servidor | Procure o código no log: a linha traz o detalhe completo. |
| Não consigo excluir um tipo, marca ou modelo | Ele está em uso por etiquetas (a mensagem diz quantas) | Mude as etiquetas antes. |
| "Este é o último administrador ativo..." | O sistema ficaria sem nenhum ADMIN | Crie ou promova outro administrador antes. |
