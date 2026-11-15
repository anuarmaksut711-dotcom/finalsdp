param([Parameter(ValueFromRemainingArguments=$true)][string[]]$AppArguments)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.ps1')
. (Join-Path $PSScriptRoot 'find-java.ps1')
& (Join-Path $script:JavaBin 'java.exe') -jar (Join-Path $PSScriptRoot 'digital-ecosystem.jar') @AppArguments
if ($LASTEXITCODE -ne 0) { throw 'Application failed.' }
