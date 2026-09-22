#!/usr/bin/env bash
# 一次尝试：将联邦 catalog 推到 dev3 Trino 权威目录并重启。
# 口令失败则退出；勿循环重试。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
HOST="${LH_DEV3_HOST:-127.0.0.1}"
USER="${LH_DEV3_USER:-datagoodeploy}"
# 默认取台账；可用 LH_DEV3_PASSWORD 覆盖。失败时请运维处理，勿改脚本盲试。
PASS="${LH_DEV3_PASSWORD:-datagoodeploy@2026}"
REMOTE="${LH_TRINO_REMOTE:-/opt/softs/lakehouse/trino}"

if ! command -v sshpass >/dev/null 2>&1; then
  echo "need sshpass (brew install sshpass / apt install sshpass)" >&2
  exit 2
fi

export SSHPASS="$PASS"
SSH=(sshpass -e ssh -o StrictHostKeyChecking=no -o PreferredAuthentications=password -o PubkeyAuthentication=no -o ConnectTimeout=15)
SCP=(sshpass -e scp -o StrictHostKeyChecking=no -o PreferredAuthentications=password -o PubkeyAuthentication=no -o ConnectTimeout=15)

echo "SSH once → ${USER}@${HOST}:${REMOTE}/catalog/"
if ! "${SSH[@]}" "${USER}@${HOST}" "test -d ${REMOTE}/catalog"; then
  echo "SSH or remote catalog dir failed — stop (do not retry wrong password)." >&2
  exit 1
fi

"${SCP[@]}" \
  "$ROOT/catalog/clickhouse.properties" \
  "$ROOT/catalog/tpch.properties" \
  "${USER}@${HOST}:${REMOTE}/catalog/"

"${SSH[@]}" "${USER}@${HOST}" "cd /opt/softs/lakehouse && ./ctl.sh restart trino"
echo "Remote apply requested. Run: bash deploy/trino/verify-federation.sh"
