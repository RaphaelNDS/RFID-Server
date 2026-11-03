@echo off
setlocal
rem ============================================================================================
rem  Para e remove o servico do Windows "Servidor RFID" (rfid-servidor).
rem  Rode como administrador. Depois disso o servidor volta a ser iniciado pelo iniciar-servidor.bat.
rem ============================================================================================

cd /d "%~dp0"
chcp 65001 >nul

net session >nul 2>&1
if errorlevel 1 (
    echo [ERRO] Rode este arquivo como administrador ^(botao direito ^> Executar como administrador^).
    goto :fim_erro
)
sc query rfid-servidor >nul 2>&1
if errorlevel 1 (
    echo O servico "Servidor RFID" nao esta instalado.
    goto :fim_erro
)

echo Parando o servico ^(o servidor fecha o banco antes de sair^)...
rfid-servidor.exe stop
echo Removendo o servico...
rfid-servidor.exe uninstall
if errorlevel 1 (
    echo [ERRO] Nao foi possivel remover o servico.
    goto :fim_erro
)
echo.
echo Pronto. Para iniciar o servidor agora, use o iniciar-servidor.bat.
pause
exit /b 0

:fim_erro
pause
exit /b 1
