#!/usr/bin/env bash
# 将指定备份目录中的 MySQL dump 恢复到临时库并 SELECT 1
# 用法：./restore-mysql-smoke.sh ops/backup/out/20260929T120000Z
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
[[ -f "$DIR/.env" ]] && source "$DIR/.env"

SRC="${1:?usage: $0 <backup-dir>}"
DUMP="$(ls "$SRC"/mysql-*.sql.gz 2>/dev/null | head -1 || true)"
[[ -n "$DUMP" ]] || { echo "no mysql-*.sql.gz in $SRC"; exit 2; }

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-7306}"
MYSQL_USER="${MYSQL_USER:-datagoo}"
: "${MYSQL_PASSWORD:?set MYSQL_PASSWORD in .env}"
SMOKE_DB="${SMOKE_DB:-lakehouse_gov_restore_smoke}"

echo "== recreate $SMOKE_DB =="
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" \
  -e "DROP DATABASE IF EXISTS \`$SMOKE_DB\`; CREATE DATABASE \`$SMOKE_DB\` DEFAULT CHARSET utf8mb4;"

echo "== restore $DUMP =="
gunzip -c "$DUMP" | mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$SMOKE_DB"

echo "== smoke SELECT =="
mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" "$SMOKE_DB" \
  -e "SELECT 1 AS ok; SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() LIMIT 5;"

echo "OK restore smoke. Optional cleanup:"
echo "  mysql ... -e \"DROP DATABASE \`$SMOKE_DB\`\""
