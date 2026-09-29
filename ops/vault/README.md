# HashiCorp Vault（波次 R4）· 试点侧车

> 门户：`lh.vault.backend=hashicorp` + `lh.vault.addr` + `lh.vault.token`  
> 默认仍为 `aes`（`ig_secret_store`）；切换前请先备份 MySQL。

## 快速启动（dev）

```bash
cp .env.example .env
docker compose up -d
# 根 token 见 .env VAULT_DEV_ROOT_TOKEN_ID
export VAULT_ADDR=http://127.0.0.1:8200
export VAULT_TOKEN=lh-dev-root-token
vault status
vault kv put secret/platform/trino/query username=admin password=change-me
```

门户 `application-local.properties`：

```properties
lh.vault.backend=hashicorp
lh.vault.addr=http://127.0.0.1:8200
lh.vault.token=lh-dev-root-token
lh.vault.kv-mount=secret
```

## 从 AES@MySQL 迁移

```bash
# 需本机 mysql 客户端 + vault CLI；读 .env.migrate
./migrate-aes-to-hashicorp.sh
```

脚本将 `ig_secret_store` 中可解密行写入 KV v2（路径不变）。PLACEHOLDER 跳过。

## 验收

- [ ] `vault kv get secret/platform/trino/query` 有数据  
- [ ] 门户切 `backend=hashicorp` 后数据源测连成功  
- [ ] 回切 `aes` 仍可用（未删 MySQL 密文前）
