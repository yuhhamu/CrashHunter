param([string]$JavaHome = "Z:\Claude\Tools\Java\jdk-21")
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\installer\secrets.ps1")
$dir = Get-SecretsDir
if (-not $dir) { throw "SECRETS が設定されていません" }
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$key = Join-Path $dir "snapshot-key.pk8"
$pub = Join-Path $PSScriptRoot "snapshot-key.pub"
if (-not (Test-Path $key)) {
    & "$JavaHome\bin\java.exe" (Join-Path $PSScriptRoot "..\installer\SignSums.java") --generate $key $pub
    if ($LASTEXITCODE -ne 0) { throw "鍵を作れませんでした" }
    Write-Host "集計データ用の鍵を作りました: $key"
}
if (-not (Test-Path $pub)) { throw "公開鍵がありません: $pub" }
$b64 = (Get-Content $key -Raw).Trim()
Push-Location $PSScriptRoot
try {
    $env:CI = "1"
    $b64 | cmd.exe /c "npx wrangler secret put SNAP_SIGN_KEY 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "サーバーに鍵を登録できませんでした" }
} finally { Pop-Location }
$b64 = $null
Write-Host "サーバーに登録しました。公開鍵: $(Get-Content $pub -Raw)"
