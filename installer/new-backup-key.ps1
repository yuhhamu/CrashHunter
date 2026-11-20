param([string]$JavaHome = "Z:\Claude\Tools\Java\jdk-21")
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "secrets.ps1")
$dir = Get-SecretsDir
if (-not $dir) { throw "SECRETS が設定されていません" }
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$key = Join-Path $dir "update-key-backup.pk8"
$pub = Join-Path $PSScriptRoot "update-key-backup.pub"
if (Test-Path $key) { throw "予備鍵はすでにあります: $key" }
& "$JavaHome\bin\java.exe" (Join-Path $PSScriptRoot "SignSums.java") --generate $key $pub
if ($LASTEXITCODE -ne 0) { throw "鍵を作れませんでした" }
Write-Host "予備鍵を作りました。"
Write-Host "  秘密鍵: $key"
Write-Host "  公開鍵: $pub"
Write-Host "秘密鍵は USB メモリなど PC の外にも保存し、可能ならこの PC からは消してください。"
