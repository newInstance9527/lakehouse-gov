# Superset Guest Token 内嵌（门户 BI）

门户看数走 **Embedded SDK + Guest Token**，不做 AUTH_DB 裸深链。做表仅 **OIDC 同 IdP**。

**权威配置副本**：仓库 [`deploy/superset/`](../../deploy/superset/)（`superset_config.py` + `.env.example` + `apply-config.sh`）。

## 快速开

```bash
cd deploy/superset
cp .env.example .env   # 填 SUPERSET_* / CORS / 可选 OIDC
./apply-config.sh      # 同步现网后 ctl.sh restart superset
```

门户 `application-*.properties`：

```properties
lh.superset.embed-enabled=true
lh.superset.embed-allowed-domains=<与 SUPERSET_CORS_ORIGINS 一致>
lh.superset.oidc-enabled=true   # Keycloak 已起 + Superset AUTH_MODE=both
lh.superset.oidc-login-path=/login/keycloak
lh.superset.guest-token-exp-sec=300
lh.superset.embed-enforce-metric-auth=true
```

现网一键（本机）：`LH_SSH_PASSWORD=… python deploy/superset/_remote_enable_oidc.py`  
若容器仍读到 `AUTH_MODE=native`：compose `environment.AUTH_MODE: both` 后  
`cd /opt/softs/lakehouse && set -a && . _shared/shared.env && set +a && cd superset && docker compose up -d --force-recreate`。

## 用户隔离（门户 SoT）

看数**不**在 Superset 再建一套用户/角色。签发用服务账号；隔离在门户 `embedSession`：

| 层 | 行为 |
|----|------|
| 身份 | Guest Token `user.username` = 门户 `account` |
| 可读闸门 | 看板须关联 `metricCodes`；当前用户对至少一条指标 `canReadMetric`（Owner / SELECT·EDIT·MANAGE） |
| 行级 | `sec_auth_grant.row_filter`（安全校验）AND 请求 `filters.dims` → Guest RLS `{dataset, clause}` |
| 封堵 | 无查询权的关联数据集、以及看板图表上未关联指标的数据集 → `1 = 0` |
| allowlist | 若远端支持 `datasets` 则只放行可读数据集；3.1.x 不支持则自动去掉重试（依赖上条 RLS） |

关闭隔离（仅应急）：`lh.superset.embed-enforce-metric-auth=false`。

列表字段：`canEmbed` / `embedBlockReason` / `allowedMetricCodes` / `deniedMetricCodes`。

## 门户信息架构

| 页面 | 路径 | 职责 |
|------|------|------|
| **看板** | `/bi` | 看数主场：列表 + 内嵌；维度筛选为页内抽屉 |
| **制作台** | `/bi/studio` | 接通数据 → 接入指标 → 建图（OIDC）→ 同步 / 收藏关联 / 复制链接 |

日常用户只进「看板」；分析师走「制作台」。勿照搬 Superset 四栏导航。

## 验收

1. 制作台接通并接入指标 → 建图 →「同步看板到门户」→ `/bi` 内嵌观看无二次登录。
2. `POST /lh/bi/embedSession` 返回 `guestToken` + `embeddedId` + `expiresInSec` + `rls` + `allowedMetricCodes`；SDK `fetchGuestToken` 会续期。
3. 无指标查询权用户 → `canEmbed=false` / embedSession 拒绝。
4. 带 `row_filter` 的 SELECT grant → Guest RLS 含该谓词；维切片 AND 合并。
5. 看板页「维度筛选」或制作台 `openDatasetWithDims` 后回看板。
6. 「建图」在未开 OIDC 时明确报错；开启后可进分析工具。
