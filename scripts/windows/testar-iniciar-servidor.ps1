# Confere o iniciar-servidor.bat de verdade, no cmd do Windows (usado pelo CI no windows-latest).
# Monta uma pasta de servidor com Java e um jar vazio e roda o .bat: ele precisa passar pelas conferências de Java,
# jar e porta e parar na do banco (no CI não há PostgreSQL), com código 1. Um erro de sintaxe do cmd (parêntese
# sem ^ dentro de um if, por exemplo) faz o .bat parar antes ou com outra mensagem, e este teste falha.
#
# Uso: powershell -File scripts\windows\testar-iniciar-servidor.ps1 [-PortaBanco 5433]
#   -PortaBanco: porta do banco que o .bat confere (para rodar numa máquina com o banco no ar, use uma porta livre)
param([int]$PortaBanco = 5433)

$ErrorActionPreference = 'Stop'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java).Source }

$pasta = Join-Path ([IO.Path]::GetTempPath()) ("rfid-bat-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force (Join-Path $pasta 'java\bin'), (Join-Path $pasta 'servidor'), (Join-Path $pasta 'config') | Out-Null
try {
    $bat = Get-Content -Raw (Join-Path $PSScriptRoot 'iniciar-servidor.bat')
    $bat = $bat.Replace('set "PORTA_BANCO=5433"', "set `"PORTA_BANCO=$PortaBanco`"")
    [IO.File]::WriteAllText((Join-Path $pasta 'iniciar-servidor.bat'), $bat, [Text.Encoding]::ASCII)
    Copy-Item $java (Join-Path $pasta 'java\bin\java.exe')
    New-Item -ItemType File (Join-Path $pasta 'servidor\rfid-server.jar') | Out-Null

    $saida = cmd /c "`"$(Join-Path $pasta 'iniciar-servidor.bat')`" /servico" 2>&1 | Out-String
    $codigo = $LASTEXITCODE
    Write-Output $saida

    if ($codigo -ne 1) { throw "O .bat terminou com código $codigo (esperado 1, na conferência do banco)" }
    if ($saida -notmatch 'O banco de dados') { throw 'O .bat parou antes da conferência do banco (erro de sintaxe do cmd?)' }
    Write-Output 'OK: o .bat passou pelas conferências de Java, jar e porta e parou na do banco, como esperado'
} finally {
    Remove-Item -Recurse -Force $pasta -ErrorAction SilentlyContinue
}
# O código 1 do .bat era o esperado: sem isto o PowerShell do CI terminaria o passo com ele 0
