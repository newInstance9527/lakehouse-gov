#!/usr/bin/env bash
# 调门户向量探针（D2）：GET /lh/knowledge/vector/probe
# 用法：
#   ./probe-vector.sh https://portal.example.com '<token>'
#   PORTAL_URL=http://127.0.0.1:82 TOKEN=... ./probe-vector.sh

set -euo pipefail

BASE="${1:-${PORTAL_URL:-http://127.0.0.1:82}}"
TOKEN="${2:-${TOKEN:-}}"
BASE="${BASE%/}"

hdr=(-H "Accept: application/json")
if [[ -n "$TOKEN" ]]; then
  hdr+=(-H "token: ${TOKEN}")
fi

code=$(curl -sS -o /tmp/lh-milvus-probe.body -w '%{http_code}' \
  --connect-timeout 5 --max-time 20 \
  "${hdr[@]}" "${BASE}/lh/knowledge/vector/probe" || echo 000)

echo "GET ${BASE}/lh/knowledge/vector/probe -> ${code}"
cat /tmp/lh-milvus-probe.body 2>/dev/null || true
echo

if [[ ! "$code" =~ ^2 ]]; then
  echo "FAIL: 门户探针失败（需登录 Token；或门户未发版）" >&2
  exit 1
fi
echo "OK: 查看 JSON 中 enabled / reachable / mode（keyword=降级，vector|hybrid=向量可用）"
exit 0
