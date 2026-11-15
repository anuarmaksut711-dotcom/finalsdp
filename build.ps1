$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'find-java.ps1')
$taskTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$taskStageDir = Join-Path $taskTempRoot ('digital-ecosystem-build-' + [guid]::NewGuid().ToString('N'))
$taskClasses = Join-Path $taskStageDir 'classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
try {
    $taskSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\ecosystem') -Filter '*.java' | Select-Object -ExpandProperty FullName)
    & (Join-Path $script:JavaBin 'javac.exe') -encoding UTF-8 --release 17 -d $taskClasses @taskSources
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
    $taskJar = Join-Path $taskStageDir 'digital-ecosystem.jar'
    & (Join-Path $script:JavaBin 'jar.exe') --create --file $taskJar --main-class ecosystem.App -C $taskClasses .
    if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed.' }
    Copy-Item -LiteralPath $taskJar -Destination (Join-Path $PSScriptRoot 'digital-ecosystem.jar') -Force
} finally {
    $taskResolvedStage = [IO.Path]::GetFullPath($taskStageDir)
    if (-not $taskResolvedStage.StartsWith($taskTempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Build staging directory is outside the temporary directory.'
    }
    if (Test-Path -LiteralPath $taskResolvedStage) { Remove-Item -LiteralPath $taskResolvedStage -Recurse -Force }
}
Write-Host 'Build successful: digital-ecosystem.jar'
