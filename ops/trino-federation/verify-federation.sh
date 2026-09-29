#!/usr/bin/env bash
# 联邦挂载探针（HTTPS Trino UI 反代）；不依赖门户。
set -euo pipefail
TRINO_URL="${TRINO_URL:-https://127.0.0.1:18080}"
USER="${TRINO_USER:-admin}"
PASS="${TRINO_PASSWORD:?set TRINO_PASSWORD}"
IMPERSONATE="${TRINO_IMPERSONATE:-superAdmin}"

post_sql() {
  local sql="$1"
  curl -sk -u "${USER}:${PASS}" \
    -H "X-Trino-User: ${IMPERSONATE}" \
    -H "X-Trino-Source: federation-verify" \
    -X POST "${TRINO_URL}/v1/statement" \
    -d "${sql}"
}

follow() {
  local json next state
  json="$(cat)"
  while true; do
    state="$(printf '%s' "$json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('stats',{}).get('state') or d.get('error',{}).get('message') or '')" 2>/dev/null || true)"
    next="$(printf '%s' "$json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('nextUri') or '')" 2>/dev/null || true)"
    data="$(printf '%s' "$json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data') or '')" 2>/dev/null || true)"
    if [[ -n "$data" && "$data" != "None" ]]; then
      printf '%s\n' "$json" | python3 -c "import sys,json; d=json.load(sys.stdin); print('DATA', d.get('data')); print('COLS', [c.get('name') for c in d.get('columns') or []])"
    fi
    if [[ "$state" == "FINISHED" || "$state" == "FAILED" || -z "$next" ]]; then
      echo "STATE=$state"
      if [[ "$state" != "FINISHED" ]]; then
        printf '%s\n' "$json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('error') or d)" 2>/dev/null || printf '%s\n' "$json"
        return 1
      fi
      return 0
    fi
    json="$(curl -sk -u "${USER}:${PASS}" -H "X-Trino-User: ${IMPERSONATE}" "$next")"
  done
}

echo "== SHOW CATALOGS =="
post_sql "SHOW CATALOGS" | follow

echo "== SHOW SCHEMAS FROM clickhouse (if mounted) =="
post_sql "SHOW SCHEMAS FROM clickhouse" | follow || echo "(clickhouse not ready — mount catalog first)"
