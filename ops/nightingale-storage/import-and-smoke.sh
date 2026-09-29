#!/usr/bin/env bash
# 导入存储夜莺规则 + 冒烟 VM 查询（生产一次性）
set -euo pipefail
N9E_URL="${N9E_URL:-http://127.0.0.1:17000}"
N9E_USER="${N9E_USER:-root}"
N9E_PASS="${N9E_PASS:?set N9E_PASS}"
VM_URL="${VM_URL:-http://127.0.0.1:8428}"
BGID="${N9E_BGID:-1}"
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "== login =="
TOKEN=$(curl -sS -X POST "$N9E_URL/api/n9e/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$N9E_USER\",\"password\":\"$N9E_PASS\"}" | sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
test -n "$TOKEN"

echo "== import prom rules (UI paste also OK) =="
# 夜莺 ≥8.2 推荐控制台「告警规则 → 导入 Prometheus 规则」粘贴 alert-rules.prom.yml
# 脚本侧尝试 n9e JSON 兼容导入
if [[ -f "$DIR/alert-rules.n9e.json" ]]; then
  curl -sS -X POST "$N9E_URL/api/n9e/busi-group/$BGID/alert-rules/import" \
    -H "Authorization: Bearer $TOKEN" -H "X-User-Token: $TOKEN" \
    -H 'Content-Type: application/json' \
    --data-binary @"$DIR/alert-rules.n9e.json" || true
fi

echo "== VM smoke =="
curl -sS "$VM_URL/api/v1/query?query=count(lh_bucket_storage_used_bytes%20or%20up)" | head -c 200
echo
echo "OK: 规则请在夜莺 UI 绑定通知；门户配 lh.observability.nightingale-password 或 Vault platform/nightingale/api"
