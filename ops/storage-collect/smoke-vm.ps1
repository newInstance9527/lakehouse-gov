# 冒烟：向 VictoriaMetrics 写一条样本并查询。
# 用法：.\smoke-vm.ps1 -VmUrl http://127.0.0.1:8428
param(
  [string]$VmUrl = $(if ($env:VM_URL) { $env:VM_URL } else { "http://127.0.0.1:8428" })
)
$VmUrl = $VmUrl.TrimEnd("/")
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$body = "lh_smoke_storage_bytes{kind=`"total`",source=`"ops-smoke`"} 1 $ts"

Write-Host "== health =="
Invoke-RestMethod -Uri "$VmUrl/health" -Method Get | Out-Host

Write-Host "== import =="
Invoke-RestMethod -Uri "$VmUrl/api/v1/import/prometheus" -Method Post `
  -ContentType "text/plain; charset=utf-8" -Body $body | Out-Null
Write-Host "imported $($body.Length) bytes"

Write-Host "== query =="
$q = [uri]::EscapeDataString("lh_smoke_storage_bytes")
$resp = Invoke-RestMethod -Uri "$VmUrl/api/v1/query?query=$q" -Method Get
$resp | ConvertTo-Json -Depth 6 | Select-Object -First 1
Write-Host "OK: VM import/query reachable at $VmUrl"
