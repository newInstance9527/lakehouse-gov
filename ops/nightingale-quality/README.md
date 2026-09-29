# 夜莺告警规则包 · 数据质量

> 配套：[`doc/数据质量-跨模块待办.md`](../../doc/数据质量-跨模块待办.md)  
> 批：门户 `addRun`/`evaluate` → VM `lh_dq_rule_*`  
> 流：Flink → `POST /lh/quality/rules/stream-probe` → VM `lh_dq_stream_*`  
> **不另建告警通道**：规则只读 VictoriaMetrics 中质量写点。

| # | 条件 | 说明 |
|---|------|------|
| 1 | `lh_dq_rule_blocked==1` | 批：DAG 阻断 |
| 2 | `lh_dq_rule_pass==0` | 批：失败 |
| 3 | `lh_dq_ok_pct<95` | 批：低于门禁 |
| 4 | 36h 无 `lh_dq_rule_pass` | 批：断流 |
| 5 | `lh_dq_stream_pass==0` | 流：探针失败（即时） |
| 6 | `lh_dq_stream_ok_pct<95` | 流：滑动窗口质量分 |
| 7 | `lh_dq_stream_lag_ms>60000` | 流：延迟 |
| 8 | 2h 无 `lh_dq_stream_pass` | 流：断流 |

## 文件

| 文件 | 说明 |
|------|------|
| `alert-rules.prom.yml` | Prometheus `groups` YAML（批 `lh-quality-h1` + 流 `lh-quality-stream`） |
| `stream-probe.example.sh` | Flink/sidecar curl 回调样例 |

## 导入

1. 夜莺控制台 → **告警规则** → **导入** → 选 Prometheus 规则。
2. 粘贴 `alert-rules.prom.yml`。
3. 选择 **VictoriaMetrics** 数据源（与存储/指标同一集群）。

## 门户侧开关

```yaml
lh:
  quality:
    gov-base-url: "http://gov:82"   # DS Worker 回调 evaluate；空且 blockOnFail=true → SHELL exit 1
    vm-write-enabled: true
  lifecycle:
    vm-import-url: "http://vm:8428"  # 空则只落 gov_dq_rule_run / 跳过 VM
```

### 批

- 作业回调：`POST /lh/quality/rules/evaluate`（写 runs + `blocked`）
- 单条回写：`POST /lh/quality/rules/runs`

### 流（Flink 旁路 / 异步探针）

```http
POST /lh/quality/rules/stream-probe
{
  "ruleId": "…",          # 或 ruleCode + tableName
  "jobId": "flink-cdc-order",
  "pass": false,
  "okPct": 92.5,
  "lagMs": 85000,
  "persistRun": true      # 默认 true；写 gov_dq_rule_run(job_run_id=stream:…)；永不 blocked
}
```

写点：`lh_dq_stream_pass` / `lh_dq_stream_ok_pct` / `lh_dq_stream_fail_ratio` / `lh_dq_stream_lag_ms`  
标签：`rule_code,table,ws,severity,job`

开工单：`POST /lh/quality/tickets` → `apply_ticket(quality_fix)`

可执行样例：[`stream-probe.example.sh`](./stream-probe.example.sh) · Flink 旁路：[`../flink-quality-probe/`](../flink-quality-probe/)

## 残留

**未探测到夜莺 / VictoriaMetrics。** 本目录为规则包交付；配齐栈后导入即可。Flink 用 `flink-quality-probe` 或 curl 回调 `stream-probe`；门户不内嵌 Flink Job。
