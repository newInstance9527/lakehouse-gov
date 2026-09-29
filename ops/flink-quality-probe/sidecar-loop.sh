#!/usr/bin/env bash
# 旁路 sidecar：周期性探测窗口指标并回调门户 stream-probe（可挂 Flink 同机 cron / k8s sidecar）
# 依赖：curl、jq（可选）；环境变量见 README
set -euo pipefail

GOV_BASE="${GOV_BASE:-http://127.0.0.1:82}"
TOKEN="${TOKEN:-}"
WS="${WS:-default}"
RULE_ID="${RULE_ID:-}"
RULE_CODE="${RULE_CODE:-NULL_CHECK}"
TABLE="${TABLE:-ods_trade.s_order}"
JOB_ID="${JOB_ID:-flink-cdc-sidecar}"
INTERVAL_SEC="${INTERVAL_SEC:-60}"
# 演示：从环境读窗口指标；真作业用 Flink metrics 或旁路 SQL 填充
OK_PCT="${OK_PCT:-98}"
LAG_MS="${LAG_MS:-1200}"
PASS="${PASS:-true}"

AUTH_HEADER=()
if [[ -n "$TOKEN" ]]; then
  AUTH_HEADER=(-H "token: ${TOKEN}")
fi

post_once() {
  local body
  body=$(cat <<EOF
{
  "ws": "${WS}",
  "ruleId": "${RULE_ID}",
  "ruleCode": "${RULE_CODE}",
  "tableName": "${TABLE}",
  "jobId": "${JOB_ID}",
  "pass": ${PASS},
  "okPct": ${OK_PCT},
  "failRatio": $(awk -v o="$OK_PCT" 'BEGIN{printf "%.2f", 100-o}'),
  "lagMs": ${LAG_MS},
  "persistRun": true,
  "message": "flink sidecar probe · lagMs=${LAG_MS}"
}
EOF
)
  curl -sS -X POST "${GOV_BASE%/}/lh/quality/rules/stream-probe" \
    -H "Content-Type: application/json" \
    "${AUTH_HEADER[@]}" \
    -d "${body}"
  echo
}

if [[ "${ONCE:-0}" == "1" ]]; then
  post_once
  exit 0
fi

echo "[lh-dq-sidecar] interval=${INTERVAL_SEC}s gov=${GOV_BASE} job=${JOB_ID}"
while true; do
  post_once || echo "[lh-dq-sidecar] post failed (soft)"
  sleep "${INTERVAL_SEC}"
done
