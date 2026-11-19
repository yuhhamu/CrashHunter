$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$port = 8790
. (Join-Path (Split-Path -Parent (Split-Path -Parent $here)) "installer\secrets.ps1")
$envFile = Get-SecretFile ".env"
if ($envFile) { $env:CH_ADMIN_ENV = $envFile }
Start-Process -FilePath "node" -ArgumentList "`"$here\serve.mjs`"" -WorkingDirectory $here -WindowStyle Minimized
Start-Sleep -Seconds 2
Start-Process "http://127.0.0.1:$port/"
Write-Host "管理画面: http://127.0.0.1:$port/ (終了するには最小化された node のウィンドウを閉じてください)"
