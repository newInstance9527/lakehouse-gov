#!/usr/bin/env bash
# LiteLLM 健康探测（D1）
# 用法：
#   LITELLM_URL=http://127.0.0.1:4000 LITELLM_MASTER_KEY=sk-... ./verify-litellm.sh
#   ./verify-litellm.sh http://litellm:4000 sk-...

set -euo pipefail

BASE="${1:-${LITELLM_URL:-http://127.0.0.1:4000}}"
KEY="${2:-${LITELLM_MASTER_KEY:-}}"
BASE="${BASE%/}"

auth=()
if [[ -n "$KEY" ]]; then
  auth=(-H "Authorization: Bearer ${KEY}")
fi

ok=0
for path in /health/liveliness /health /v1/models; do
  code=$(curl -sS -o /tmp/lh-litellm-health.body -w '%{http_code}' \
    --connect-timeout 5 --max-time 15 \
    "${auth[@]}" "${BASE}${path}" || echo 000)
  echo "GET ${BASE}${path} -> ${code}"
  if [[ "$code" =~ ^2 ]]; then
    ok=1
    head -c 200 /tmp/lh-litellm-health.body 2>/dev/null || true
    echo
    break
  fi
done

if [[ "$ok" -ne 1 ]]; then
  echo "FAIL: LiteLLM 不可达（检查 compose / 防火墙 / master key）" >&2
  exit 1
fi
echo "OK: LiteLLM 健康"
exit 0
