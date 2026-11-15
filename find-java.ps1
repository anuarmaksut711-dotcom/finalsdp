$ErrorActionPreference = 'Stop'
$script:JavaBin = $null
if ($env:JAVA_HOME) {
    $script:JavaBin = Join-Path $env:JAVA_HOME 'bin'
} elseif (Get-Command javac.exe -ErrorAction SilentlyContinue) {
    $script:JavaBin = Split-Path -Parent (Get-Command javac.exe).Source
} else {
    $taskJdkRoot = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path -LiteralPath $taskJdkRoot) {
        $taskJdkCandidates = Get-ChildItem -LiteralPath $taskJdkRoot -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } | Sort-Object Name
        if ($taskJdkCandidates) { $script:JavaBin = Join-Path $taskJdkCandidates[0].FullName 'bin' }
    }
}
if (-not $script:JavaBin) { throw 'JDK 17+ required. Set JAVA_HOME to a JDK folder, or select a JDK in IntelliJ.' }
foreach ($taskTool in @('java.exe', 'javac.exe', 'jar.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $script:JavaBin $taskTool))) {
        throw "Incomplete JDK: $taskTool is missing from $script:JavaBin. Set JAVA_HOME to a complete JDK 17+ directory."
    }
}
$taskJavacVersion = & (Join-Path $script:JavaBin 'javac.exe') --version
if ($LASTEXITCODE -ne 0 -or $taskJavacVersion -notmatch '^javac (\d+)' -or [int]$Matches[1] -lt 17) {
    throw 'JDK 17+ is required. Select a newer JDK with JAVA_HOME.'
}
