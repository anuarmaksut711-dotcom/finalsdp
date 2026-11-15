$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.ps1')
. (Join-Path $PSScriptRoot 'find-java.ps1')
& (Join-Path $script:JavaBin 'java.exe') '-Djava.awt.headless=true' -jar (Join-Path $PSScriptRoot 'digital-ecosystem.jar') --self-test
if ($LASTEXITCODE -ne 0) { throw 'Self-test failed.' }
