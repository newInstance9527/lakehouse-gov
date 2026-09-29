# 夜莺 · ETL / DS degrade 推送

> 波次 N1：门户 `NightingaleClient.pushEvent` + `IgEtlRunAlertBuilder`  
> 配套：`lh.observability.nightingale-*`（见 `application-lh.yml` / `application.properties`）

## 通道

| 配置 | 说明 |
|------|------|
| `lh.observability.nightingale-push-enabled` | 总开关（默认 true） |
| `lh.observability.nightingale-webhook-url` | 优先：自定义 webhook（JSON：title/message/severity/…） |
| `lh.observability.nightingale-url` + 登录 | 无 webhook 时尝试 `{url}/v1/n9e/event/push` |

失败 **soft-fail**：不挡 ETL 发布/运行事务；告警载荷仍带 `pushed` / `degraded` / `opsPath`。

## 冒烟

1. 配置 webhook 或夜莺 URL + Vault `platform/nightingale/api` 口令  
2. 人为失败一次 DS 运行或调 `IgEtlRunAlertBuilder.build(...)`  
3. 夜莺侧可见事件；门户告警对象 `pushed=true`（或 `degraded=true` 且有 `pushMessage`）

AI 巡检（N7）复用同一 `pushEvent` 通道。
