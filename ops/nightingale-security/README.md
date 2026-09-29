# 夜莺告警规则包 · 安全 / Vault 轮换

> 配套：全栈文档 §35.2  
> 门户 `POST /lh/sec/vault/rotate` 失败 → VM `lh_vault_rotate_fail` + 工单 `vault_rotate_fail`  
> **现网无夜莺/VM 时 soft-fail**，规则包仍可导入。

| # | 条件 | 说明 |
|---|------|------|
| 1 | `lh_vault_rotate_fail==1` | 轮换失败（P1） |
| 2 | 24h 无 `lh_vault_rotate_ok` | 可选：久未成功轮换（预警） |

## 文件

| 文件 | 说明 |
|------|------|
| `alert-rules.prom.yml` | Prometheus `groups` YAML |

## 导入

1. 夜莺 → **告警规则** → **导入 Prometheus 规则**
2. 粘贴 `alert-rules.prom.yml`
3. 数据源选 VictoriaMetrics（与质量/存储同一集群）
4. 挂通知通道 IM + 工单（§35.2）

## 指标契约

```
lh_vault_rotate_ok{vault_path,kind} 1|0
lh_vault_rotate_fail{vault_path,kind} 1|0
```

`kind`：`datasource` / `job_sa` / `platform`
