#!/usr/bin/env bash
# Milvus 健康探测（D2）
# 用法：
#   MILVUS_HTTP=http://127.0.0.1:9091 ./verify-milvus.sh
#   ./verify-milvus.sh http://127.0.0.1:9091
#   ./verify-milvus.sh http://127.0.0.1:9091 http://127.0.0.1:19530

set -euo pipefail

HTTP_BASE="${1:-${MILVUS_HTTP:-http://127.0.0.1:9091}}"
GRPC_HINT="${2:-${MILVUS_URI:-http://127.0.0.1:19530}}"
HTTP_BASE="${HTTP_BASE%/}"

ok=0
for path in /healthz /api/v1/health; do
  code=$(curl -sS -o /tmp/lh-milvus-health.body -w '%{http_code}' \
    --connect-timeout 5 --max-time 15 \
    "${HTTP_BASE}${path}" || echo 000)
  echo "GET ${HTTP_BASE}${path} -> ${code}"
  if [[ "$code" =~ ^2 ]]; then
    ok=1
    head -c 200 /tmp/lh-milvus-health.body 2>/dev/null || true
    echo
    break
  fi
done

if [[ "$ok" -ne 1 ]]; then
  echo "FAIL: Milvus HTTP 不可达（检查 compose / 防火墙 / MILVUS_HTTP_PORT）" >&2
  exit 1
fi

# TCP 探活 gRPC 端口（门户 ConnectParam URI 用此端口）
host_port="${GRPC_HINT#*://}"
host_port="${host_port%%/*}"
host="${host_port%%:*}"
port="${host_port##*:}"
if [[ -n "$host" && -n "$port" && "$host" != "$port" ]]; then
  if (echo >/dev/tcp/"$host"/"$port") >/dev/null 2>&1; then
    echo "OK: gRPC 端口可达 ${host}:${port}（门户 lh.ai.milvus-uri=${GRPC_HINT}）"
  else
    echo "WARN: gRPC ${host}:${port} 未通；HTTP 健康已 OK，请核对端口映射" >&2
  fi
fi

echo "OK: Milvus 健康"
exit 0
