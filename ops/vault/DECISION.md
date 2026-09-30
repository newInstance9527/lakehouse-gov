# Vault 后端决策（波次 O2）

> 配套：`ops/vault/README.md` · `lh.vault.backend`

## 试点结论（2026-09-29）

| 项 | 决策 |
|----|------|
| 默认后端 | **aes**（`ig_secret_store`） |
| HashiCorp | **可选**：侧车 compose 已备；切流前必须备份 MySQL + 跑 `migrate-aes-to-hashicorp.sh` |
| 生产目标 | 正式对外前切 `hashicorp` 或企业 KMS；AES key 仅 ENV |

## 切流勾选

- [x] `ops/backup/backup-core.sh` 已跑通当日 dump（2026-09-30 演练）  
- [ ] migrate 干跑 / 正式写入 KV 成功  
- [ ] 门户 `lh.vault.backend=hashicorp` + addr/token  
- [ ] 数据源测连通过  
- [ ] 回切 aes 预案（未删 MySQL 密文）  

> **O+5（2026-09-30）**：试点 **不切** HashiCorp，继续 **aes**；上表 HashiCorp 项保留给正式对外前。

