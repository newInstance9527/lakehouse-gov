# 夜莺告警规则包 · 指标日波动（F1 / M2）

> 配套：[`doc/指标中心-引擎执行.md`](../../../doc/指标中心-引擎执行.md) Phase M2 · 门户写 VM `lh_metric_*`  
> **不另建告警通道**：规则只读 VictoriaMetrics 中采样点。

| # | 条件 | 现网有夜莺+VM 时 | 本交付（无夜莺） |
|---|------|-----------------|------------------|
| 1 | `lh_metric_anomaly==1` | 告警平台组 | 规则已写 |
| 2 | `|lh_metric_change_pct|≥40` | P0/P1 | 规则已写 |
| 3 | 36h 无 `lh_metric_value` | 采样断流 | 规则已写 |

## 文件

| 文件 | 说明 |
|------|------|
| `alert-rules.prom.yml` | Prometheus `groups` YAML，夜莺 ≥8.2 导入 |

## 导入

1. 夜莺控制台 → **告警规则** → **导入** → 选 Prometheus 规则。
2. 粘贴 `alert-rules.prom.yml`。
3. 选择 **VictoriaMetrics** 数据源（与存储趋势同一集群）。

## 门户侧开关

```yaml
lh:
  metric:
    query-cache-enabled: true
    query-cache-ttl-seconds: 60
    sample-daily-enabled: true   # 默认 false
    sample-daily-cron: "0 15 4 * * ?"
    anomaly-threshold-pct: 20
  lifecycle:
    vm-import-url: "http://vm:8428"   # 空则只落 gov_metric_sample
```

手动补跑：`POST /lh/metric/anomaly/rerun?ws=default`  
摘要：`GET /lh/metric/{code}/anomaly`

## 残留

**未探测到夜莺 / VictoriaMetrics。** 本目录为规则包交付；配齐栈后导入即可。采样默认关闭，避免无 Trino 环境空跑。

同内容副本：`deploy/nightingale-metric/`（若仓库根有 deploy 镜像）。
