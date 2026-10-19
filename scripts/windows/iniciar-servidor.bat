@echo off
setlocal
rem ============================================================================================
rem  Inicia o servidor RFID fora do IntelliJ.
rem
rem  - Pasta de trabalho = pasta deste .bat (RFID_Servidor): usa .\data (banco, certificado HTTPS,
rem    logs, backups) e .\config\application.properties (chaves dos coletores e opcoes).
rem  - Roda em primeiro plano: a janela fica aberta enquanto o servidor estiver no ar.
rem    Para parar: Ctrl+C nesta janela (o servidor fecha o banco antes de sair).
rem  - Para virar servico do Windows depois (NSSM, WinSW...): use este .bat com o parametro
rem    /servico (nao espera tecla ao terminar). Ex.: iniciar-servidor.bat /servico
rem  - Versao nova do servidor: pare o servidor, troque servidor\rfid-server.jar e inicie de novo.
rem ============================================================================================

rem Java usado: o mesmo liberado no Firewall do Windows (regra de entrada para este java.exe).
rem Trocou o Java? Libere o novo java.exe no firewall, senao os coletores nao conectam.
set "JAVA=C:\Users\Dev\.jdks\ms-17.0.20\bin\java.exe"
set "JAR=%~dp0servidor\rfid-server.jar"
set "MEMORIA=-Xmx1g"
rem Porta HTTPS conferida antes de iniciar (a mesma de rfid.https.porta no config, padrao 8443)
set "PORTA=8443"

cd /d "%~dp0"
chcp 65001 >nul

if not exist "%JAVA%" (
    echo [ERRO] Java nao encontrado: %JAVA%
    echo        Ajuste a variavel JAVA no inicio deste arquivo.
    goto :fim_erro
)
if not exist "%JAR%" (
    echo [ERRO] Servidor nao encontrado: %JAR%
    echo        Copie o rfid-server-*.jar gerado em rfid-server\build\libs para servidor\rfid-server.jar
    goto :fim_erro
)
if not exist "config\application.properties" (
    echo [AVISO] config\application.properties nao existe: o servidor sobe sem as chaves dos coletores
)

rem Ja tem servidor nesta porta (ex.: o do IntelliJ)? Dois ao mesmo tempo disputariam a porta e o banco
netstat -ano | findstr /r /c:":%PORTA% .*LISTENING" >nul
if not errorlevel 1 (
    echo [ERRO] A porta %PORTA% ja esta em uso: outro servidor RFID ^(ex.: o do IntelliJ^) esta rodando.
    echo        Pare o outro antes de iniciar este.
    goto :fim_erro
)

echo Iniciando o servidor RFID em %CD%
echo Log: %CD%\data\logs\rfid-server.log
echo Para parar: Ctrl+C nesta janela.
echo.

"%JAVA%" %MEMORIA% -Dfile.encoding=UTF-8 -jar "%JAR%"
set "SAIDA=%ERRORLEVEL%"

echo.
echo Servidor encerrado (codigo %SAIDA%).
if /i not "%~1"=="/servico" pause
exit /b %SAIDA%

:fim_erro
if /i not "%~1"=="/servico" pause
exit /b 1
