#!/usr/bin/env bash
# 核心库日备：MySQL lakehouse_gov + 可选 PostgreSQL 库列表
# 用法：
#   cp .env.example .env   # 填连接
#   ./backup-core.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
[[ -f "$DIR/.env" ]] && source "$DIR/.env"

BACKUP_ROOT="${BACKUP_ROOT:-$DIR/out}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_ROOT/$STAMP"
mkdir -p "$OUT"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-7306}"
MYSQL_USER="${MYSQL_USER:-datagoo}"
MYSQL_DB="${MYSQL_DB:-lakehouse_gov}"
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD in .env}"

echo "== MySQL dump $MYSQL_DB @ $MYSQL_HOST:$MYSQL_PORT =="
mysqldump \
  -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" \
  --single-transaction --routines --triggers --set-gtid-purged=OFF \
  "$MYSQL_DB" | gzip -c > "$OUT/mysql-${MYSQL_DB}.sql.gz"
ls -lh "$OUT/mysql-${MYSQL_DB}.sql.gz"

if [[ "${PG_ENABLE:-0}" == "1" ]]; then
  : "${PG_HOST:?}" "${PG_PORT:?}" "${PG_USER:?}" "${PG_PASSWORD:?}"
  PG_DBS="${PG_DBS:-openmetadata_db,dolphinscheduler,gravitino,hive_metastore,marquez,superset}"
  export PGPASSWORD="$PG_PASSWORD"
  IFS=',' read -ra DBS <<< "$PG_DBS"
  for db in "${DBS[@]}"; do
    db="$(echo "$db" | xargs)"
    [[ -z "$db" ]] && continue
    echo "== pg_dump $db =="
    pg_dump -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$db" --no-owner --format=custom \
      -f "$OUT/pg-${db}.dump"
  done
fi

# 轻量清单
{
  echo "stamp=$STAMP"
  echo "mysql=$MYSQL_DB"
  echo "host=$(hostname)"
  echo "rpo_target_hours=24"
} > "$OUT/MANIFEST.txt"

echo "OK: $OUT"
echo "Next: ./restore-mysql-smoke.sh $OUT"
