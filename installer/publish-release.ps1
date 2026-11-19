param([switch]$DryRun, [string]$Repo = "yuhhamu/CrashHunter")
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "build\release"
$ver = (Select-String -Path (Join-Path $root "build.gradle.kts") -Pattern 'version = "([^"]+)"').Matches[0].Groups[1].Value
$tag = "v$ver"
"version $ver"

$files = @("CrashHunter-Setup-$ver.exe", "CrashHunter-$ver-portable.zip", "SHA256SUMS.txt", "SHA256SUMS.txt.sig") | ForEach-Object { Join-Path $out $_ }
foreach ($f in $files) { if (-not (Test-Path $f)) { throw "missing $f" } }
$sums = Get-Content (Join-Path $out "SHA256SUMS.txt")
foreach ($f in $files[0..1]) {
    $name = Split-Path $f -Leaf
    $h = (Get-FileHash -Algorithm SHA256 $f).Hash.ToLower()
    if (-not ($sums -contains "$h  $name")) { throw "hash mismatch $name" }
    "ok $name $h"
}
$java = "Z:\Claude\Tools\Java\jdk-21\bin\java.exe"
$verify = Join-Path $PSScriptRoot "verify-sums.java"
$sig = & $java $verify (Join-Path $out "SHA256SUMS.txt") (Join-Path $out "SHA256SUMS.txt.sig") 2>$null
if ($sig -ne "true") { throw "signature check failed" }
"ok signature"

$log = Get-Content (Join-Path $root "CHANGELOG.md") -Encoding UTF8
$section = New-Object System.Collections.Generic.List[string]
$inside = $false
foreach ($l in $log) {
    if ($l -match '^## ') { if ($inside) { break }; if ($l -match "^## $([regex]::Escape($ver))(\b|\(|$)") { $inside = $true; continue } }
    if ($inside) { $section.Add($l) }
}
if ($section.Count -eq 0) { throw "no CHANGELOG section for $ver" }
$tail = Get-Content (Join-Path $PSScriptRoot "release-notes-tail.md") -Encoding UTF8
$body = (($section -join "`n").Trim() + "`n`n" + (($tail -join "`n").Replace("{ver}", $ver)).Trim())
$notes = Join-Path $out "release-notes-$ver.md"
[IO.File]::WriteAllText($notes, $body, (New-Object Text.UTF8Encoding($false)))
"notes $notes"

$token = $null
. (Join-Path $PSScriptRoot "secrets.ps1")
$envFile = Get-SecretFile ".env"
if ($envFile) { foreach ($l in Get-Content $envFile) { if ($l -match '^GH_RELEASE_TOKEN=(.+)$') { $token = $Matches[1].Trim() } } }
if (-not $token) { "token missing (GH_RELEASE_TOKEN in %SECRETS%\CrashHunter\.env)"; if ($DryRun) { exit 0 } else { throw "token missing" } }
$h = @{ Authorization = "Bearer $token"; Accept = "application/vnd.github+json"; "X-GitHub-Api-Version" = "2022-11-28"; "User-Agent" = "crashhunter-release" }
$api = "https://api.github.com/repos/$Repo"
$info = Invoke-RestMethod -Uri $api -Headers $h
"repo $($info.full_name) push=$($info.permissions.push)"
if (-not $info.permissions.push) { throw "token cannot write to $Repo" }
$existing = $null
try { $existing = Invoke-RestMethod -Uri "$api/releases/tags/$tag" -Headers $h } catch { }
if ($existing) { throw "release $tag already exists" }
"release $tag does not exist yet"
if ($DryRun) { "dry run: stop here"; exit 0 }

$payload = @{ tag_name = $tag; target_commitish = "main"; name = ("CrashHunter $ver" + $(if ($ver.StartsWith("0.")) { " beta" } else { "" })); body = $body; draft = $false; prerelease = $false; make_latest = "true" } | ConvertTo-Json
$rel = Invoke-RestMethod -Method Post -Uri "$api/releases" -Headers $h -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($payload))
"created $($rel.html_url)"
foreach ($f in $files) {
    $name = Split-Path $f -Leaf
    $up = "https://uploads.github.com/repos/$Repo/releases/$($rel.id)/assets?name=$([uri]::EscapeDataString($name))"
    $a = Invoke-RestMethod -Method Post -Uri $up -Headers $h -ContentType "application/octet-stream" -InFile $f
    "uploaded $name $($a.size)"
}
$check = Invoke-RestMethod -Uri "$api/releases/$($rel.id)" -Headers $h
foreach ($f in $files) {
    $name = Split-Path $f -Leaf
    $asset = $check.assets | Where-Object { $_.name -eq $name }
    $local = "sha256:" + (Get-FileHash -Algorithm SHA256 $f).Hash.ToLower()
    if ($asset.digest -and $asset.digest -ne $local) { throw "uploaded file differs: $name" }
    "verified $name"
}
"done $($check.html_url)"
