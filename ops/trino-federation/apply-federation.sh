#!/usr/bin/env bash
# 将联邦 catalog 属性拷入 Trino catalog 目录并 recreate 容器（本机或已在 dev3 上执行）。
# 权威现网路径：/opt/softs/lakehouse/trino
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
CATALOG_DST="${1:-$ROOT/catalog}"
SRC="$ROOT/catalog"

if [[ ! -d "$SRC" ]]; then
  echo "missing $SRC" >&2
  exit 1
fi

mkdir -p "$CATALOG_DST"
for f in clickhouse.properties tpch.properties; do
  if [[ -f "$SRC/$f" ]]; then
    cp -f "$SRC/$f" "$CATALOG_DST/$f"
    echo "installed $f → $CATALOG_DST/"
  fi
done

# 若在 compose 编排目录（含 docker-compose.yml）则 recreate
if [[ -f "$ROOT/docker-compose.yml" ]]; then
  echo "Recreate lh-trino to pick up catalog mounts..."
  (cd "$ROOT" && docker compose up -d --force-recreate trino)
  sleep 5
  echo "Verify: docker exec lh-trino ls /etc/trino/catalog"
  docker exec lh-trino ls /etc/trino/catalog || true
else
  echo "No docker-compose.yml here; catalogs copied only."
  echo "On host with ctl.sh: cd /opt/softs/lakehouse && ./ctl.sh restart trino"
fi

echo "Done. Next: SHOW CATALOGS → 门户 POST /catalog-map → lh.trino.query-catalogs 含 clickhouse"
