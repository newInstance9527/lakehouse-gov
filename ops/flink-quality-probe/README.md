# Flink 流式质量探针（旁路 → 门户 stream-probe）

> 门户 API：`POST /lh/quality/rules/stream-probe` → VictoriaMetrics `lh_dq_stream_*` → 夜莺 `lh-quality-stream`  
> **不**阻断 DAG；与批 evaluate 分工。

## 文件

| 文件 | 说明 |
|------|------|
| `LhDqStreamProbeReporter.java` | JDK 纯 HTTP 上报器（可粘进 Flink fat-jar） |
| `sidecar-loop.sh` | 同机 sidecar / cron 循环回调 |
| `../nightingale-quality/stream-probe.example.sh` | 单次 curl 样例 |

## Flink 作业内

1. 将 `LhDqStreamProbeReporter.java` 拷入作业模块（或打成小 jar 挂 `lib/`）。
2. 在 `ProcessFunction` / 定时器里算窗口 `okPct`、`lagMs`，调用 `report(probe)`。
3. 环境变量：`LH_GOV_URL`（门户基址）、可选 `token`。

## Sidecar（不改作业代码）

```bash
export GOV_BASE=http://gov:82
export RULE_CODE=NULL_CHECK
export TABLE=ods_trade.s_order
export JOB_ID=flink-cdc-order
export INTERVAL_SEC=60
# 演示一次性：
ONCE=1 ./sidecar-loop.sh
# 常驻：
./sidecar-loop.sh
```

真实环境用 Flink REST / Prometheus 拉 lag，再填 `OK_PCT`/`LAG_MS`/`PASS`。

## 验收

- 门户质量页「流式探针」摘要 `runCount` 增加
- VM 出现 `lh_dq_stream_pass{job=...}`
- 夜莺导入 `lh-quality-stream` 后可告警
