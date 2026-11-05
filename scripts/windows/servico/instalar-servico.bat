@echo off
setlocal
rem ============================================================================================
rem  Instala e inicia o servico do Windows "Servidor RFID" (rfid-servidor).
rem
rem  - Rode como administrador (botao direito > Executar como administrador).
rem  - Precisa, nesta pasta: rfid-servidor.exe (o WinSW-x64.exe da versao 2.12.0 renomeado) e
rem    rfid-servidor.xml. Baixe em https://github.com/winsw/winsw/releases/tag/v2.12.0
rem  - Pare antes o servidor aberto pelo iniciar-servidor.bat (Ctrl+C na janela dele).
rem  - Depois de instalado: inicia sozinho ao ligar o computador e volta sozinho se cair.
rem    Parar/iniciar: services.msc ("Servidor RFID") ou, como administrador,
rem    rfid-servidor.exe stop / rfid-servidor.exe start
rem ============================================================================================

cd /d "%~dp0"
chcp 65001 >nul

net session >nul 2>&1
if errorlevel 1 (
    echo [ERRO] Rode este arquivo como administrador ^(botao direito ^> Executar como administrador^).
    goto :fim_erro
)
if not exist "rfid-servidor.exe" (
    echo [ERRO] rfid-servidor.exe nao encontrado nesta pasta.
    echo        Baixe o WinSW-x64.exe da versao 2.12.0 em https://github.com/winsw/winsw/releases/tag/v2.12.0
    echo        e salve aqui com o nome rfid-servidor.exe
    goto :fim_erro
)
if not exist "rfid-servidor.xml" (
    echo [ERRO] rfid-servidor.xml nao encontrado nesta pasta.
    goto :fim_erro
)
rem Java do servico: o da pasta java\ (padrao do rfid-servidor.xml). Sem barra invertida na busca: o findstr
rem a trata como caractere especial
findstr /c:"<executable>%%BASE%%" rfid-servidor.xml >nul
if not errorlevel 1 if not exist "..\java\bin\java.exe" (
    echo [ERRO] Java nao encontrado: %~dp0..\java\bin\java.exe
    echo        Copie um JDK 17 inteiro para a pasta java ao lado do iniciar-servidor.bat ^(ex.: o conteudo de
    echo        C:\Program Files\Microsoft\jdk-17...^) e libere java\bin\java.exe no Firewall do Windows.
    echo        Ou troque o caminho em executable no rfid-servidor.xml.
    goto :fim_erro
)
if not exist "..\servidor\rfid-server.jar" (
    echo [ERRO] Servidor nao encontrado: %~dp0..\servidor\rfid-server.jar
    goto :fim_erro
)

rem Servidor aberto pelo .bat ou pelo IntelliJ: os dois disputariam a porta e o banco
netstat -ano | findstr /r /c:":8443 .*LISTENING" >nul
if not errorlevel 1 (
    sc query rfid-servidor >nul 2>&1
    if errorlevel 1 (
        echo [ERRO] A porta 8443 ja esta em uso: o servidor ainda esta aberto ^(janela do iniciar-servidor.bat
        echo        ou IntelliJ^). Pare com Ctrl+C na janela dele e rode este arquivo de novo.
        goto :fim_erro
    )
)

sc query rfid-servidor >nul 2>&1
if errorlevel 1 (
    echo Instalando o servico "Servidor RFID"...
    rfid-servidor.exe install
    if errorlevel 1 (
        echo [ERRO] Nao foi possivel instalar o servico.
        goto :fim_erro
    )
) else (
    echo O servico "Servidor RFID" ja esta instalado.
)

echo Iniciando o servico...
rfid-servidor.exe start
rfid-servidor.exe status
echo.
echo Pronto. O servidor leva uns 20 segundos para responder. Log: %~dp0..\data\logs\rfid-server.log
echo Nao use mais o iniciar-servidor.bat enquanto o servico estiver instalado.
pause
exit /b 0

:fim_erro
pause
exit /b 1
