#!/usr/bin/env bash
# 冒烟：向 VictoriaMetrics 写一条样本并查询。用法：VM_URL=http://127.0.0.1:8428 ./smoke-vm.sh
set -euo pipefail
VM_URL="${VM_URL:-http://127.0.0.1:8428}"
VM_URL="${VM_URL%/}"
TS=$(($(date -u +%s) * 1000))
BODY="lh_smoke_storage_bytes{kind=\"total\",source=\"ops-smoke\"} 1 ${TS}"

echo "== health =="
curl -sfS "${VM_URL}/health" && echo

echo "== import =="
curl -sfS -X POST "${VM_URL}/api/v1/import/prometheus" \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary "${BODY}"
echo "imported ${#BODY} bytes"

echo "== query =="
curl -sfS --get "${VM_URL}/api/v1/query" \
  --data-urlencode 'query=lh_smoke_storage_bytes' | head -c 800
echo
echo "OK: VM import/query reachable at ${VM_URL}"
