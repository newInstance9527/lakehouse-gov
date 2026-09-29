#!/usr/bin/env bash
# Flink / sidecar 流式探针回调样例（写 VM lh_dq_stream_*；不阻断 DAG）
# 用法：GOV_BASE=http://gov:82 TOKEN=xxx ./stream-probe.example.sh
set -euo pipefail

GOV_BASE="${GOV_BASE:-http://127.0.0.1:82}"
TOKEN="${TOKEN:-}"
WS="${WS:-default}"
RULE_ID="${RULE_ID:-}"
RULE_CODE="${RULE_CODE:-NULL_CHECK}"
TABLE="${TABLE:-ods_trade.s_order}"
JOB_ID="${JOB_ID:-flink-cdc-demo}"

AUTH_HEADER=()
if [[ -n "$TOKEN" ]]; then
  AUTH_HEADER=(-H "token: ${TOKEN}")
fi

BODY=$(cat <<EOF
{
  "ws": "${WS}",
  "ruleId": "${RULE_ID}",
  "ruleCode": "${RULE_CODE}",
  "tableName": "${TABLE}",
  "jobId": "${JOB_ID}",
  "pass": false,
  "okPct": 92.5,
  "failRatio": 7.5,
  "lagMs": 85000,
  "persistRun": true,
  "message": "stream probe sample · lag high"
}
EOF
)

curl -sS -X POST "${GOV_BASE}/lh/quality/rules/stream-probe" \
  -H "Content-Type: application/json" \
  "${AUTH_HEADER[@]}" \
  -d "${BODY}"
echo
