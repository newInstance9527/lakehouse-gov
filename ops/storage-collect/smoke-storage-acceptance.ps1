# Storage acceptance smoke: nightingale routes + VM bucket series + optional portal checks.
# Usage:
#   .\smoke-storage-acceptance.ps1 -VmUrl http://dev1:8428 -PortalUrl http://localhost:82 -Token <jwt>
# PortalUrl/Token optional: then only checks local rule files + VM.

param(
  [string]$VmUrl = $(if ($env:VM_URL) { $env:VM_URL } else { "http://127.0.0.1:8428" }),
  [string]$PortalUrl = $(if ($env:PORTAL_URL) { $env:PORTAL_URL } else { "" }),
  [string]$Token = $(if ($env:PORTAL_TOKEN) { $env:PORTAL_TOKEN } else { "" })
)

$ErrorActionPreference = "Stop"
$VmUrl = $VmUrl.TrimEnd("/")
$fail = 0

function Ok([string]$msg) { Write-Host "[OK] $msg" -ForegroundColor Green }
function Bad([string]$msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; $script:fail++ }

Write-Host "== 1) nightingale alert-rules route=owner/platform =="
$rules = Join-Path $PSScriptRoot "..\nightingale-storage\alert-rules.prom.yml"
$txt = Get-Content -Raw -LiteralPath $rules
if (($txt -match 'route:\s*owner') -and ($txt -match 'route:\s*platform')) {
  Ok "alert-rules.prom.yml has route=owner and route=platform"
} else {
  Bad "alert-rules.prom.yml missing route owner/platform"
}
$notify = Join-Path $PSScriptRoot "..\nightingale-storage\notify-routes.example.yml"
if (Test-Path -LiteralPath $notify) {
  Ok "notify-routes.example.yml present"
} else {
  Bad "notify-routes.example.yml missing"
}

Write-Host "== 2) VM bucket metrics (Categraf) =="
try {
  Invoke-RestMethod -Uri "$VmUrl/health" -Method Get | Out-Null
  Ok "VM health $VmUrl"
} catch {
  Bad "VM health failed: $_"
}
$q = [uri]::EscapeDataString('lh_bucket_storage_used_bytes or minio_bucket_usage_total_bytes or minio_cluster_usage_buckets_total_bytes')
try {
  $resp = Invoke-RestMethod -Uri "$VmUrl/api/v1/query?query=$q" -Method Get
  $n = 0
  if ($resp.data.result) { $n = @($resp.data.result).Count }
  if ($n -gt 0) { Ok "VM bucket used series count=$n" }
  else { Bad "VM has no bucket used series (check Categraf MinIO)" }
} catch {
  Bad "VM query failed: $_"
}

if ($PortalUrl) {
  Write-Host "== 3) GET /lh/lifecycle/storage/buckets source=vm: =="
  $PortalUrl = $PortalUrl.TrimEnd("/")
  $headers = @{}
  if ($Token) { $headers["Authorization"] = "Bearer $Token" }
  try {
    $body = Invoke-RestMethod -Uri "$PortalUrl/lh/lifecycle/storage/buckets" -Headers $headers -Method Get
    $data = if ($null -ne $body.data) { $body.data } else { $body }
    $src = [string]$data.source
    if ($src.StartsWith("vm:")) {
      Ok "buckets.source=$src count=$($data.count)"
      if ($src -eq "vm:empty") {
        Write-Host "  note: VM configured but empty - $($data.hint)" -ForegroundColor Yellow
      }
    } else {
      Bad "buckets.source must start with vm:, got=$src"
    }
  } catch {
    Bad "portal buckets failed: $_"
  }

  Write-Host "== 4) overview.reclaimAxes deep links =="
  try {
    $ov = Invoke-RestMethod -Uri "$PortalUrl/lh/lifecycle/overview" -Headers $headers -Method Get
    $data = if ($null -ne $ov.data) { $ov.data } else { $ov }
    $axes = $data.reclaimAxes
    $lake = [string]$axes.lakePartitionArchive.deepLink
    $exp = [string]$axes.exportExpireReclaim.deepLink
    if (($lake -match 'focus=archive') -and ($exp -match 'focus=expire')) {
      Ok "reclaimAxes lake=$lake export=$exp"
    } else {
      Bad "reclaimAxes deepLink mismatch lake=$lake export=$exp"
    }
  } catch {
    Bad "overview failed: $_"
  }
} else {
  Write-Host "== 3/4) skip portal (no -PortalUrl) ==" -ForegroundColor Yellow
}

if ($fail -gt 0) {
  Write-Host "FAILED ($fail)" -ForegroundColor Red
  exit 1
}
Write-Host "ALL OK" -ForegroundColor Green
