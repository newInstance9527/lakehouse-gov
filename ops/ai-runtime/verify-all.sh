#!/usr/bin/env bash
# 联合探测 LiteLLM + Milvus（波次 A）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ok=0
fail=0

run() {
  local name="$1"; shift
  echo "==> $name"
  if "$@"; then
    echo "OK $name"
    ok=$((ok + 1))
  else
    echo "FAIL $name"
    fail=$((fail + 1))
  fi
}

if [[ -x "$ROOT/litellm/verify-litellm.sh" ]]; then
  run litellm "$ROOT/litellm/verify-litellm.sh"
else
  echo "SKIP litellm verify script missing"
fi

if [[ -x "$ROOT/milvus/verify-milvus.sh" ]]; then
  run milvus "$ROOT/milvus/verify-milvus.sh"
else
  echo "SKIP milvus verify script missing"
fi

echo "summary ok=$ok fail=$fail"
exit "$([[ "$fail" -eq 0 ]] && echo 0 || echo 1)"
