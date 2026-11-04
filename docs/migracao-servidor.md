# Migração do servidor para outra máquina

Passo a passo para levar o servidor RFID (servidor + banco PostgreSQL no Docker + certificado HTTPS) de um
computador para outro **sem perder dados e sem reconfigurar o certificado nos coletores**.

Tempo estimado: 1 a 2 horas, a maior parte preparando a máquina nova. Os coletores ficam sem servidor só entre os
passos 3 e 7 (uns 30 minutos). Nesse intervalo eles continuam lendo e guardam tudo para enviar depois (até 72 h).

Nos exemplos, a pasta do servidor é `C:\RFID_Servidor`. Use a pasta que preferir: o `iniciar-servidor.bat` usa a
pasta onde ele está. **Recomendado: fora do perfil de um usuário** (não em `Documentos` ou na Área de Trabalho):
assim o servidor não depende daquele usuário existir, e dá para restringir a pasta `data` (certificado, senha dele
e backups) só aos administradores da máquina (botão direito > Propriedades > Segurança).

---

## 0. Decida o endereço (IP) da máquina nova

| Opção | O que muda nos coletores |
|---|---|
| **Mesmo IP da máquina antiga** (recomendado, ex.: `172.16.242.22`) | **Nada.** Os coletores continuam conectando sozinhos. |
| IP novo | Em cada coletor: Configurações (com o PIN) → trocar o **IP do servidor** → **Salvar** → **Testar conexão**. |

Para manter o IP: a máquina nova precisa de **IP fixo** igual ao da antiga (peça à TI/rede ou configure a reserva
no roteador/DHCP). As duas **não podem ficar ligadas na rede com o mesmo IP ao mesmo tempo**: troque o IP da
antiga (ou desligue-a da rede) antes de ligar a nova.

> Com IP novo, quem digitar o endereço com `http://` no navegador é redirecionado para o IP antigo (é o que está
> gravado no certificado). Use sempre `https://<ip-novo>:8443`. O app do coletor não é afetado: ele confere a
> impressão digital do certificado, não o endereço.

---

## 1. Prepare a máquina nova (antes do dia da troca)

1. **Docker Desktop** instalado e funcionando (com WSL 2). Em *Settings → General*, marque
   **Start Docker Desktop when you sign in**.
2. **Java 17**: se a pasta `RFID_Servidor\java\` já existe na máquina antiga, copie-a junto (passo 2) e não
   precisa instalar nada. Senão, instale o mesmo usado hoje (Microsoft Build of OpenJDK 17) e copie a pasta dele
   (ex.: `C:\Program Files\Microsoft\jdk-17...`) para `RFID_Servidor\java\` na máquina nova.
3. **IP fixo** conforme o passo 0, e a rede como **Privada** (ou Domínio) no Windows: em rede *Pública* o firewall
   bloqueia os coletores.
4. **Porta 5433 livre** (é a do banco). Se já houver outro PostgreSQL nela, troque `RFID_DB_PORTA` no `.env` e
   `PORTA_BANCO` no `iniciar-servidor.bat`, e descomente e ajuste `spring.datasource.url` no
   `config\application.properties` (padrão `jdbc:postgresql://localhost:5433/rfid`).

---

## 2. Separe o que vai junto (na máquina antiga)

Copie da pasta `RFID_Servidor` da máquina antiga:

| O que | Por quê | Se esquecer |
|---|---|---|
| `data\rfid-https.p12` e `data\rfid-https.p12.senha` | Certificado HTTPS | **Todos os coletores param** e precisam confirmar um certificado novo, um a um. |
| `config\application.properties` | Chaves dos coletores e senha do banco | Os coletores são recusados. |
| `docker\.env` e `docker\docker-compose.yml` | Senha e configuração do banco | O banco não sobe com a senha certa. |
| `docker\backups\` | Backup do banco (é ele que leva os dados) | **Perde os dados.** |
| `data\app\` | APKs da tela "App dos coletores" | Basta enviar o APK de novo pela tela. |
| `servidor\rfid-server.jar` e `iniciar-servidor.bat` | O servidor | Gere de novo (`./gradlew bootJar`). |
| `servico\` | Serviço do Windows (opcional, passo 9) | Copie de `scripts\windows\servico`. |

Não precisa levar: `data\rfid-db.mv.db` / `rfid-db.trace.db` (banco H2 antigo, sem uso), `data\logs\`, os
`servidor\*-anterior.jar` e a pasta `rfid-server` (o código fica no GitHub).

> `config\application.properties`, `docker\.env` e o `.p12` são **segredos**. Leve num pendrive seu ou numa pasta
> de rede restrita, e apague a cópia intermediária depois.

---

## 3. No dia: pare a máquina antiga e faça o backup final

1. Avise os operadores. Nos coletores, espere sumir o aviso "leitura(s) guardada(s) no coletor, aguardando envio"
   (tudo enviado). Não é obrigatório (o que ficar guardado é enviado depois), mas deixa a conferência mais fácil.
2. Pare o servidor: **Ctrl+C** na janela do `iniciar-servidor.bat` (ou `services.msc` → "Servidor RFID" → Parar,
   se estiver como serviço). A partir daqui os coletores ficam sem servidor.
3. Faça o backup final do banco **com o servidor parado** (nada mais muda depois dele). Na pasta `docker`:

   ```powershell
   docker exec rfid-backup /backup.sh
   dir backups\last
   ```

   Anote o nome do arquivo mais novo em `backups\last` (ex.: `rfid-20261110-093012.dump`). **É ele que será
   restaurado.**
4. Copie os arquivos da tabela do passo 2 para a máquina nova, mantendo a mesma estrutura de pastas.
5. Pare o banco da máquina antiga (os dados continuam lá, para voltar atrás se precisar):

   ```powershell
   docker compose stop
   ```

6. Se for manter o mesmo IP: troque o IP da máquina antiga ou desligue-a da rede agora.

---

## 4. Suba o banco na máquina nova e restaure os dados

Na pasta `C:\RFID_Servidor\docker` da máquina nova:

1. Suba **só o banco** (não o backup: ao subir, ele faria um backup do banco ainda vazio e o `rfid-latest.dump`
   passaria a apontar para esse backup vazio):

   ```powershell
   docker compose up -d postgres
   docker compose ps
   ```

   Espere o `rfid-postgres` ficar `healthy`.
2. Restaure o backup final anotado no passo 3 (troque o nome do arquivo):

   ```powershell
   docker cp backups\last\rfid-20261110-093012.dump rfid-postgres:/tmp/migracao.dump
   docker exec rfid-postgres pg_restore -U rfid -d rfid --no-owner /tmp/migracao.dump
   docker exec rfid-postgres rm /tmp/migracao.dump
   ```

   Use o arquivo com a data no nome, não o `rfid-latest.dump` (é um atalho, e o `docker cp` do Windows não copia
   atalhos).
3. Confira se os dados vieram (compare com a máquina antiga):

   ```powershell
   docker exec rfid-postgres psql -U rfid -d rfid -c "select (select count(*) from usuarios) usuarios, (select count(*) from tags) etiquetas, (select count(*) from leituras_android) leituras"
   ```

4. Agora suba também o backup automático:

   ```powershell
   docker compose up -d
   ```

---

## 5. Ajuste o Java e o firewall

1. Confira que existe `RFID_Servidor\java\bin\java.exe` (passo 1). O `.bat` mostra na janela o Java que usou.
2. Libere a entrada desse `java.exe` no Firewall do Windows. Ou aceite o aviso do firewall na primeira execução
   (marque **Redes privadas**), ou, num PowerShell **como administrador** (o `.bat` avisa e mostra este comando
   com o caminho certo quando não encontra a regra):

   ```powershell
   New-NetFirewallRule -DisplayName "Servidor RFID (Java)" -Direction Inbound -Action Allow -Program "C:\caminho\RFID_Servidor\java\bin\java.exe" -Profile Private,Domain
   ```

---

## 6. Inicie o servidor e confira

1. Dê dois cliques no `iniciar-servidor.bat`.
2. No log (`data\logs\rfid-server.log` ou na janela), confira:
   - **`Certificado HTTPS carregado de ...`**: certo, é o certificado antigo.
   - Se aparecer **`Certificado HTTPS gerado em ...`**, **pare o servidor (Ctrl+C)**: o `.p12` não foi copiado
     para `data\`. Apague o `rfid-https.p12` e o `.senha` novos, copie os da máquina antiga e inicie de novo.
   - A impressão digital SHA-256 tem de ser a mesma de antes
     (hoje: `63:0F:85:87:...:C0:C0:92:36`).
3. No navegador da máquina nova, abra `https://localhost:8443`, entre como ADMIN e confira:
   - **Status**: banco no ar e a mesma impressão digital do certificado;
   - as etiquetas, os usuários e o catálogo estão lá;
   - **App dos coletores**: as versões aparecem (se faltar o APK, envie de novo).

---

## 7. Coletores

- **Mesmo IP:** nada a fazer. Em cada coletor, abra as Configurações e toque em **Testar conexão** (ou só espere:
  as leituras guardadas são enviadas sozinhas).
- **IP novo:** em cada coletor, Configurações (pede o PIN) → **IP do servidor** com o IP novo → **Salvar** →
  **Testar conexão**.

Em nenhum dos casos o coletor deve pedir para confirmar certificado. Se aparecer **"certificado diferente"**,
**não confirme**: o servidor novo está com outro certificado (volte ao passo 6.2).

Depois, faça uma leitura de teste em cada coletor e confira que ela chega ao servidor.

---

## 8. Se der errado: voltar para a máquina antiga

1. Pare o servidor novo (Ctrl+C) e, se estiver usando o mesmo IP, tire a máquina nova da rede.
2. Na antiga: devolva o IP, `docker compose start` na pasta `docker` e inicie o `iniciar-servidor.bat`.
3. Os coletores voltam sozinhos (mesmo IP) ou volte o IP nas Configurações deles.

Só funciona sem perder nada se nenhuma leitura nova tiver sido gravada na máquina nova.

---

## 9. Depois da migração

1. **Serviço do Windows** (sobe sozinho ao ligar o computador e não cai ao fechar a janela): veja
   "Serviço do Windows" no [README](../README.md#serviço-do-windows). O serviço usa o mesmo Java da pasta
   `RFID_Servidor\java\` (o `instalar-servico.bat` confere).
2. **Backup fora da máquina:** copie periodicamente `docker\backups`, `data\backups`, o `.p12` + `.senha`,
   `config\application.properties` e `docker\.env` para outro lugar (outro disco, rede ou nuvem da empresa).
3. Confira no dia seguinte se o backup automático rodou: tem de haver um arquivo novo em `docker\backups\daily`.
4. **Máquina antiga:** mantenha os dados por uma ou duas semanas, para o caso de precisar voltar. Depois apague
   os contêineres e o volume (`docker compose down -v` na pasta `docker`) e a pasta `RFID_Servidor`, que tem
   segredos.

---

## Lista rápida

- [ ] IP decidido (mesmo IP ou novo) e máquina nova com IP fixo e rede Privada
- [ ] Docker Desktop (inicia com o Windows) e Java 17 na máquina nova
- [ ] Servidor antigo parado → `docker exec rfid-backup /backup.sh` → nome do dump anotado
- [ ] Copiados: `.p12` + `.senha`, `config\application.properties`, `docker\.env`, `docker-compose.yml`, `docker\backups`, `data\app`, JAR, `.bat` e `java\` (se existir)
- [ ] `docker compose stop` na antiga (e IP trocado/fora da rede, se for o mesmo IP)
- [ ] Na nova: `docker compose up -d postgres` → `pg_restore` → contagem conferida → `docker compose up -d`
- [ ] `RFID_Servidor\java\` com o JDK 17 e firewall liberado para `java\bin\java.exe`
- [ ] Log com **"Certificado HTTPS carregado"** e a mesma impressão digital
- [ ] Login web, Status, etiquetas e App dos coletores conferidos
- [ ] Coletores: Testar conexão (e IP trocado, se for IP novo) e uma leitura de teste
- [ ] Serviço do Windows instalado e backup fora da máquina
