param([switch]$Desktop)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.ps1')
. (Join-Path $PSScriptRoot 'find-java.ps1')
$taskVerification = Join-Path $PSScriptRoot 'tmp\verification'
$taskClasses = Join-Path $taskVerification 'classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
$taskLog = Join-Path $taskVerification 'results.txt'
Set-Content -LiteralPath $taskLog -Value '' -Encoding UTF8
$taskJar = Join-Path $PSScriptRoot 'digital-ecosystem.jar'
$taskChecks = @('CliSmoke.java', 'HuntAnimationSmoke.java', 'GameUiSmoke.java', 'UiSmoke.java', 'ExportSmoke.java', 'PatternDemo.java') |
    ForEach-Object { Join-Path (Join-Path $PSScriptRoot 'docs') $_ }
& (Join-Path $script:JavaBin 'javac.exe') -encoding UTF-8 --release 17 -cp $taskJar -d $taskClasses @taskChecks
if ($LASTEXITCODE -ne 0) { throw 'Verification compilation failed.' }
$taskClasspath = $taskJar + [IO.Path]::PathSeparator + $taskClasses
function Invoke-Verification([string[]]$JavaArguments) {
    & (Join-Path $script:JavaBin 'java.exe') @JavaArguments 2>&1 | Tee-Object -FilePath $taskLog -Append
    if ($LASTEXITCODE -ne 0) { throw 'Verification failed. See tmp/verification/results.txt.' }
}
Push-Location -LiteralPath $PSScriptRoot
try {
    Invoke-Verification -JavaArguments @('-Djava.awt.headless=true', '-jar', $taskJar, '--self-test')
    Invoke-Verification -JavaArguments @('-Djava.awt.headless=true', '-cp', $taskClasspath, 'PatternDemo')
    Invoke-Verification -JavaArguments @('-Djava.awt.headless=true', '-cp', $taskClasspath, 'ecosystem.HuntAnimationSmoke')
    Invoke-Verification -JavaArguments @('-Djava.awt.headless=true', '-cp', $taskClasspath, 'CliSmoke', $taskJar)
    if ($Desktop) { Invoke-Verification -JavaArguments @('-cp', $taskClasspath, 'GameUiSmoke') }
} finally {
    Pop-Location
}
Write-Host 'Verification passed. Results: tmp/verification/results.txt'
