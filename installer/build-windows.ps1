param(
    [string]$JavaHome = "Z:\Claude\Tools\Java\jdk-21",
    [string]$Gradle = "Z:\Claude\Tools\Gradle\gradle-8.14.2\bin\gradle.bat",
    [string]$Iscc = "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "build\release"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$log = Join-Path $out "build.log"
function L($m) { "$(Get-Date -Format 'HH:mm:ss') $m" | Add-Content $log -Encoding utf8 }
"" | Out-File $log -Encoding utf8
$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
Set-Location $root
if (-not (Test-Path $Gradle)) { $Gradle = Join-Path $root "gradlew.bat" }
. (Join-Path $PSScriptRoot "secrets.ps1")
$envFile = Get-SecretFile ".env"
L ("secrets: " + [bool](Get-SecretsDir) + " env: " + [bool]$envFile)
if ($envFile) {
    foreach ($l in Get-Content $envFile) {
        if ($l -match '^(CH_DB_TOKEN|CH_CF_APP_KEY)=(.+)$') { Set-Item -Path "env:$($Matches[1])" -Value $Matches[2] }
    }
}
L ("db token: " + [bool]$env:CH_DB_TOKEN)

L "gradle test jpackageImage verifier"
& cmd /c "`"$Gradle`" --no-daemon --console=plain test :app:jpackageImage :verifier:installDist >> `"$log`" 2>&1"
L "gradle exit $LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { L "ABORT"; exit 1 }

$ver = (Select-String -Path (Join-Path $root "build.gradle.kts") -Pattern 'version = "([^"]+)"').Matches[0].Groups[1].Value
$image = Join-Path $root "app\build\jpackage\CrashHunter"
$imageDest = Join-Path $root "build\jpackage\CrashHunter"
if (Test-Path $imageDest) { Remove-Item -Recurse -Force $imageDest }
New-Item -ItemType Directory -Force -Path (Split-Path $imageDest) | Out-Null
Copy-Item -Recurse $image $imageDest
L "app image ok ($ver)"

if (Test-Path $Iscc) {
    & cmd /c "`"$Iscc`" /DAppVersion=$ver `"$root\installer\CrashHunter.iss`" >> `"$log`" 2>&1"
    L "iscc exit $LASTEXITCODE"
} else { L "ISCC not found: $Iscc (installer skipped)" }

$zip = Join-Path $out "CrashHunter-$ver-portable.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Copy-Item (Join-Path $root "LICENSE"), (Join-Path $root "NOTICE.md"), (Join-Path $root "README.md") $imageDest
Compress-Archive -Path $imageDest -DestinationPath $zip
L "zip ok"
$sums = Join-Path $out "SHA256SUMS.txt"
$targets = Get-ChildItem $out -File | Where-Object { $_.Name -like "CrashHunter-*$ver*" -and ($_.Extension -eq ".exe" -or $_.Extension -eq ".zip") }
$lines = foreach ($t in $targets) { "{0}  {1}" -f (Get-FileHash -Algorithm SHA256 $t.FullName).Hash.ToLower(), $t.Name }
[IO.File]::WriteAllLines($sums, [string[]]$lines)
L "sha256 ok ($($targets.Count))"
$signKey = Get-SecretFile "update-key.pk8"
$sig = "$sums.sig"
if (Test-Path $sig) { Remove-Item -Force $sig }
if ($signKey) {
    & "$JavaHome\bin\java.exe" (Join-Path $root "installer\SignSums.java") $signKey $sums >> $log 2>&1
    L ("sign exit $LASTEXITCODE " + [bool](Test-Path $sig))
} else { L "sign skipped (no key)" }
Get-ChildItem $out | ForEach-Object { L ("{0} {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) }
L "END"
