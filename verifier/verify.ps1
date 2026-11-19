param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$java = "Z:\Claude\Tools\Java\jdk-21"
if (Test-Path $java) { $env:JAVA_HOME = $java; $env:Path = "$java\bin;$env:Path" }
$bin = Join-Path $root "verifier\build\install\crashhunter-verify\bin\crashhunter-verify.bat"
if (-not (Test-Path $bin)) {
    $gradle = "Z:\Claude\Tools\Gradle\gradle-8.14.2\bin\gradle.bat"
    if (-not (Test-Path $gradle)) { $gradle = Join-Path $root "gradlew.bat" }
    Push-Location $root
    & $gradle --no-daemon -q :verifier:installDist
    Pop-Location
}
[Console]::OutputEncoding = [Text.Encoding]::UTF8
& $bin @Rest
exit $LASTEXITCODE
