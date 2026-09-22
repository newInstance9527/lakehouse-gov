#!/usr/bin/env bash
# 门户 AI 模型巡检（D1）
# 用法：
#   ./patrol-models.sh https://portal.example.com '<BearerToken>' [ws]
# 环境变量：PORTAL_BASE / PORTAL_TOKEN / WS

set -euo pipefail

BASE="${1:-${PORTAL_BASE:-}}"
TOKEN="${2:-${PORTAL_TOKEN:-}}"
WS="${3:-${WS:-}}"

if [[ -z "$BASE" || -z "$TOKEN" ]]; then
  echo "用法: $0 <portalBase> <bearerToken> [ws]" >&2
  exit 2
fi
BASE="${BASE%/}"

url="${BASE}/lh/ai/models/patrol"
if [[ -n "$WS" ]]; then
  url="${url}?ws=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$WS")"
fi

code=$(curl -sS -o /tmp/lh-ai-patrol.json -w '%{http_code}' \
  --connect-timeout 5 --max-time 120 \
  -X POST "$url" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" || echo 000)

echo "POST ${url} -> ${code}"
if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
import json,sys
try:
  d=json.load(open("/tmp/lh-ai-patrol.json"))
except Exception as e:
  print("body read fail", e); sys.exit(1)
data=d.get("data") if isinstance(d, dict) and "data" in d else d
if not isinstance(data, dict):
  print(json.dumps(d, ensure_ascii=False)[:500]); sys.exit(1)
print("litellmOk=", data.get("litellmOk"),
      "checked=", data.get("checked"),
      "warn=", data.get("warnCount"),
      "fail=", data.get("failCount"))
for it in (data.get("items") or [])[:20]:
  print(f"  - {it.get('name')} ok={it.get('ok')} status={it.get('status')} vault={it.get('vaultOk')} {it.get('message')}")
PY
else
  head -c 800 /tmp/lh-ai-patrol.json; echo
fi

if [[ ! "$code" =~ ^2 ]]; then
  echo "FAIL: 巡检接口非 2xx" >&2
  exit 1
fi
echo "OK: 巡检已执行"
exit 0
