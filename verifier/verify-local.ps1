param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest)
. (Join-Path (Split-Path -Parent $PSScriptRoot) "installer\secrets.ps1")
$envFile = Get-SecretFile ".env"
if (-not $envFile) { Write-Error "CH_DB_ADMIN_TOKEN の .env が %SECRETS%\CrashHunter にありません"; exit 1 }
& (Join-Path $PSScriptRoot "verify.ps1") @Rest --admin-env $envFile
exit $LASTEXITCODE
