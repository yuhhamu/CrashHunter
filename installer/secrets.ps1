function Get-SecretsDir {
    $root = [Environment]::GetEnvironmentVariable("SECRETS", "Machine")
    if (-not $root) { $root = [Environment]::GetEnvironmentVariable("SECRETS", "User") }
    if (-not $root) { $root = $env:SECRETS }
    if (-not $root) { return $null }
    return (Join-Path $root "CrashHunter")
}

function Get-SecretFile([string]$Name) {
    $dir = Get-SecretsDir
    if (-not $dir) { return $null }
    $p = Join-Path $dir $Name
    if (Test-Path $p) { return $p }
    return $null
}
