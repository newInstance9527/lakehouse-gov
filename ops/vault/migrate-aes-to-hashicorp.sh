#!/usr/bin/env bash
# 将 ig_secret_store（AES）密文解密后写入 HashiCorp KV v2
# 依赖：mysql 客户端、vault CLI、Python3（解密）
# 配置：cp .env.migrate.example .env.migrate
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$DIR/.env.migrate"

: "${MYSQL_HOST:?}" "${MYSQL_USER:?}" "${MYSQL_PASSWORD:?}" "${MYSQL_DB:?}"
: "${LH_VAULT_AES_KEY:?}" "${VAULT_ADDR:?}" "${VAULT_TOKEN:?}"
export VAULT_ADDR VAULT_TOKEN
KV_MOUNT="${KV_MOUNT:-secret}"

TMP="$(mktemp)"
mysql -h "$MYSQL_HOST" -P "${MYSQL_PORT:-7306}" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B \
  -e "SELECT vault_path, secret_cipher FROM ig_secret_store WHERE secret_cipher IS NOT NULL AND secret_cipher<>'PLACEHOLDER'" \
  "$MYSQL_DB" > "$TMP"

python3 - "$TMP" "$LH_VAULT_AES_KEY" "$KV_MOUNT" <<'PY'
import hashlib, json, subprocess, sys
from pathlib import Path

rows_path, aes_key, mount = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import unpad
    import base64
except ImportError:
    # fallback: call openssl-less path via pycryptodome optional; use hutool-incompatible — require pycryptodome
    print("Need pycryptodome: pip install pycryptodome", file=sys.stderr)
    sys.exit(2)

key = hashlib.md5(aes_key.encode()).hexdigest()[:16].encode()

def decrypt(b64: str) -> dict:
    raw = base64.b64decode(b64)
    # Hutool AES default: ECB/PKCS5 — Crypto.Cipher AES ECB
    cipher = AES.new(key, AES.MODE_ECB)
    plain = unpad(cipher.decrypt(raw), AES.block_size)
    return json.loads(plain.decode())

ok = skip = fail = 0
for line in Path(rows_path).read_text(encoding="utf-8").splitlines():
    if not line.strip():
        continue
    path, cipher = line.split("\t", 1)
    try:
        data = decrypt(cipher.strip())
    except Exception as e:
        print("FAIL decrypt", path, e)
        fail += 1
        continue
    # vault kv put secret/path k=v ...
    args = ["vault", "kv", "put", f"{mount}/{path}"]
    for k, v in data.items():
        args.append(f"{k}={v}")
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        print("FAIL put", path, r.stderr)
        fail += 1
    else:
        print("OK", path)
        ok += 1
print(f"done ok={ok} fail={fail}")
sys.exit(1 if fail else 0)
PY

rm -f "$TMP"
echo "Migration finished. Switch portal: lh.vault.backend=hashicorp"
