#!/usr/bin/env bash
# 将夜莺账号写入门户 Vault 路径（ig_secret_store / LhVaultClient 约定）
# 用法：在可调门户管理接口的环境执行，或手工写入 Vault UI
set -euo pipefail
echo "Vault path: platform/nightingale/api"
echo "Fields: username / password"
echo "Example curl (replace TOKEN with portal admin JWT):"
cat <<'EOF'
curl -sS -X POST 'http://<portal>/dev/config/...' # 或运维直接写 ig_secret_store
# 亦可临时：
# lh.observability.nightingale-password=***
EOF
