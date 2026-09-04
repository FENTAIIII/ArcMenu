param(
    [string]$Maven = 'mvn',
    [string]$Jdk21 = $env:ARCMENU_JDK21,
    [string]$Jdk25 = $env:ARCMENU_JDK25
)

$ErrorActionPreference = 'Stop'
$requiredJdks = @{ '21' = $Jdk21; '25' = $Jdk25 }
foreach ($entry in $requiredJdks.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace($entry.Value)) {
        throw "Set ARCMENU_JDK$($entry.Key) or pass -Jdk$($entry.Key) before running the version matrix."
    }
}
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$matrix = @(
    @{ Minecraft = '1.21.1'; Paper = '1.21.1-R0.1-SNAPSHOT'; Java = $Jdk21 },
    @{ Minecraft = '1.21.4'; Paper = '1.21.4-R0.1-SNAPSHOT'; Java = $Jdk21 },
    @{ Minecraft = '1.21.6'; Paper = '1.21.6-R0.1-SNAPSHOT'; Java = $Jdk21 },
    @{ Minecraft = '1.21.11'; Paper = '1.21.11-R0.1-SNAPSHOT'; Java = $Jdk21 },
    @{ Minecraft = '26.1.2'; Paper = '26.1.2.build.74-stable'; Java = $Jdk25 },
    @{ Minecraft = '26.2'; Paper = '26.2.build.111-stable'; Java = $Jdk25 }
)

$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path
$results = [System.Collections.Generic.List[object]]::new()
try {
    foreach ($entry in $matrix) {
        if (-not (Test-Path -LiteralPath $entry.Java -PathType Container)) {
            throw "Required JDK is missing: $($entry.Java)"
        }
        $env:JAVA_HOME = $entry.Java
        $env:Path = (Join-Path $entry.Java 'bin') + ';' + $originalPath
        $started = [System.Diagnostics.Stopwatch]::StartNew()
        & $Maven -B -ntp -q "-Dpaper.version=$($entry.Paper)" clean test
        $exitCode = $LASTEXITCODE
        $started.Stop()
        $results.Add([pscustomobject]@{
            Minecraft = $entry.Minecraft
            PaperApi = $entry.Paper
            Java = (& java --version | Select-Object -First 1)
            Result = if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' }
            Seconds = [math]::Round($started.Elapsed.TotalSeconds, 2)
        })
        if ($exitCode -ne 0) { break }
    }
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:Path = $originalPath
}

$results | Format-Table -AutoSize
if ($results.Count -ne $matrix.Count -or $results.Result -contains 'FAIL') { exit 1 }
