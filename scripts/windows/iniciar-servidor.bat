@echo off
setlocal
rem ============================================================================================
rem  Inicia o servidor RFID fora do IntelliJ.
rem
rem  - Pasta de trabalho = pasta deste .bat (RFID_Servidor): usa .\data (banco, certificado HTTPS,
rem    logs, backups) e .\config\application.properties (chaves dos coletores e opcoes).
rem  - Roda em primeiro plano: a janela fica aberta enquanto o servidor estiver no ar.
rem    Para parar: Ctrl+C nesta janela (o servidor fecha o banco antes de sair).
rem  - Servico do Windows (inicia sozinho ao ligar o computador): pasta servico\ com o
rem    instalar-servico.bat (WinSW). Com o servico instalado, nao use este .bat. Ver README.
rem    O parametro /servico (nao espera tecla ao terminar) fica para outras ferramentas de servico.
rem  - Versao nova do servidor: pare o servidor, troque servidor\rfid-server.jar e inicie de novo.
rem ============================================================================================

rem Java usado: primeiro o JDK proprio do servidor, na pasta java\ ao lado deste .bat (copie um JDK 17 inteiro
rem para la: vai junto numa migracao e nao muda quando o IntelliJ atualiza os JDKs dele). Sem essa pasta, usa
rem o caminho de JAVA_RESERVA. O java.exe usado precisa estar liberado no Firewall do Windows (regra de
rem entrada), senao os coletores nao conectam: este .bat avisa quando nao encontra a regra.
set "JAVA=%~dp0java\bin\java.exe"
set "JAVA_RESERVA=C:\Users\Dev\.jdks\ms-17.0.20\bin\java.exe"
if not exist "%JAVA%" set "JAVA=%JAVA_RESERVA%"
set "JAR=%~dp0servidor\rfid-server.jar"
set "MEMORIA=-Xmx1g"
rem Porta HTTPS conferida antes de iniciar (a mesma de rfid.https.porta no config, padrao 8443)
set "PORTA=8443"
rem Porta do banco PostgreSQL no Docker (a mesma do RFID_DB_PORTA no docker.env, padrao 5433)
set "PORTA_BANCO=5433"

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
    echo [ERRO] A porta %PORTA% ja esta em uso: outro servidor RFID ^(o servico "Servidor RFID" ou o IntelliJ^) esta rodando.
    echo        Com o servico instalado, use services.msc em vez deste .bat. Senao, pare o outro antes.
    goto :fim_erro
)

rem O banco roda no Docker (docker\docker-compose.yml): sem ele o servidor nao sobe
netstat -ano | findstr /r /c:":%PORTA_BANCO% .*LISTENING" >nul
if errorlevel 1 (
    echo [ERRO] O banco de dados ^(PostgreSQL na porta %PORTA_BANCO%^) nao esta no ar.
    echo        Abra o Docker Desktop e, na pasta do docker-compose.yml, rode: docker compose up -d
    goto :fim_erro
)

rem Regra do firewall para este java.exe (so avisa: o servidor sobe igual, mas os coletores nao conectam).
rem Pelo PowerShell: o findstr trata a barra invertida do caminho como caractere especial e nunca acha.
rem O -eq do PowerShell ignora maiusculas (o Windows grava o caminho da regra em minusculas).
rem So vale regra ativa e de entrada: 0 = liberado, 1 = nenhuma regra que libera, 2 = regra de BLOQUEIO (o Windows
rem cria uma quando alguem clica em Cancelar no aviso do firewall; bloqueio vence liberacao).
powershell -NoProfile -Command "$r = Get-NetFirewallApplicationFilter | Where-Object { $_.Program -eq $env:JAVA } | Get-NetFirewallRule | Where-Object { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' }; if ($r | Where-Object { $_.Action -eq 'Block' }) { exit 2 }; if ($r | Where-Object { $_.Action -eq 'Allow' }) { exit 0 }; exit 1" >nul 2>&1
if errorlevel 2 (
    echo [AVISO] Uma regra do Firewall do Windows BLOQUEIA %JAVA%
    echo         Os coletores nao vao conectar. Em "Firewall do Windows com Seguranca Avancada" ^> Regras de Entrada,
    echo         apague ou desative as regras de bloqueio do java.exe ^(ou, num PowerShell como administrador^):
    echo         Get-NetFirewallApplicationFilter ^| Where-Object Program -eq "%JAVA%" ^| Get-NetFirewallRule ^| Where-Object Action -eq Block ^| Remove-NetFirewallRule
    echo.
) else if errorlevel 1 (
    echo [AVISO] Nenhuma regra ativa do Firewall do Windows libera a entrada para %JAVA%
    echo         Os coletores nao vao conectar. Num PowerShell como administrador:
    echo         New-NetFirewallRule -DisplayName "Servidor RFID (Java)" -Direction Inbound -Action Allow -Program "%JAVA%" -Profile Private,Domain
    echo.
)

echo Java: %JAVA%
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
