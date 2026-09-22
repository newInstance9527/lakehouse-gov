# 夜莺告警规则包 · 数据质量（H1）

> 配套：[`doc/数据质量-跨模块待办.md`](../../doc/数据质量-跨模块待办.md) · 门户 `addRun`/`evaluate` 写 VM `lh_dq_*`  
> **不另建告警通道**：规则只读 VictoriaMetrics 中质量写点。

| # | 条件 | 现网有夜莺+VM 时 | 本交付（无夜莺） |
|---|------|-----------------|------------------|
| 1 | `lh_dq_rule_blocked==1` | P0/P1 阻断 | 规则已写 |
| 2 | `lh_dq_rule_pass==0` | 失败告警 | 规则已写 |
| 3 | `lh_dq_ok_pct<95` | 低于门禁 | 规则已写 |
| 4 | 36h 无 `lh_dq_rule_pass` | 断流 | 规则已写 |

## 文件

| 文件 | 说明 |
|------|------|
| `alert-rules.prom.yml` | Prometheus `groups` YAML，夜莺 ≥8.2 导入 |

## 导入

1. 夜莺控制台 → **告警规则** → **导入** → 选 Prometheus 规则。
2. 粘贴 `alert-rules.prom.yml`。
3. 选择 **VictoriaMetrics** 数据源（与存储/指标同一集群）。

## 门户侧开关

```yaml
lh:
  quality:
    gov-base-url: "http://gov:82"   # DS Worker 回调 evaluate；空则 SHELL 跳过运行时裁决
    vm-write-enabled: true
  lifecycle:
    vm-import-url: "http://vm:8428"  # 空则只落 gov_dq_rule_run
```

作业回调：`POST /lh/quality/rules/evaluate`（写 runs + `blocked`）  
单条回写：`POST /lh/quality/rules/runs`  
开工单：`POST /lh/quality/tickets` → `apply_ticket(quality_fix)`

## 残留

**未探测到夜莺 / VictoriaMetrics。** 本目录为规则包交付；配齐栈后导入即可。流式探针 → 夜莺仍开放（H1 只覆盖批/DAG）。

同内容副本：`deploy/nightingale-quality/`（若仓库根有 deploy 镜像）。
