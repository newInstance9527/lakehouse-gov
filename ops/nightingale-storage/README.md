# 夜莺告警规则包 · 存储趋势 / 生命周期（C4）

> 配套：`doc/存储趋势.md` §4.4 / §5.2 · `doc/存储趋势-跨模块待办.md` §2.7 ·
> `doc/生命周期-跨模块待办.md` §2.5 · `ops/categraf-minio`（桶指标）· 表级日批 `lh_table_storage_*`。
>
> **不另建告警通道**：规则只读 VictoriaMetrics 中已有（或契约约定）指标。

## 验收清单

| # | 条件 | 现网有夜莺+VM 时 | 本交付（无夜莺） |
|---|------|------------------|------------------|
| 1 | 桶 / 表 TTF &lt; 45d → 平台组 | 规则触发 + 通知 | 规则表达式与指标名对齐 |
| 2 | 配额 / 桶水位 ≥ 80% | 同上 | 桶水位可立刻用；空间配额见残留 |
| 3 | 表级采集断流（24h 无点） | 同上 | PromQL 已写 |
| 4 | 小文件 &gt;30% / 环比恶化（compaction） | 同上 | 对齐 `lh_table_storage_small_file_ratio` |
| 5 | 与门户同源 | 同读 `lh_*`，无第二套引擎 | 契约表见下 |

## 指标对齐

| 规则意图 | PromQL 主指标 | 来源 |
|----------|---------------|------|
| 表 TTF &lt; 45 | `lh_table_storage_days_to_full{quantile="p95"}` | C2 日批写回 |
| 桶 TTF &lt; 45 | `lh_bucket_storage_used_bytes` / `capacity_bytes` 推算 | C3 Categraf + capacity |
| 桶水位 ≥ 80% | `used / capacity` | 同上 |
| 空间配额 ≥ 80% | `lh_ws_storage_used_bytes` / `lh_ws_storage_quota_bytes` | **契约名**；见残留 |
| 采集断流 | `lh_table_storage_bytes{kind="total"}` 的 `timestamp` / `absent_over_time` | C1 日批 |
| 小文件 / compaction | `lh_table_storage_small_file_ratio`、`avg_file_bytes` | C1 |

标签约定：表级 `fqtn` / `ws` / `layer`；桶级 `bucket`。规则 `append_tags`：`domain=` · `route=platform`。

## 文件

| 文件 | 用途 |
|------|------|
| `alert-rules.prom.yml` | **首选**：Prometheus `groups` YAML，夜莺 ≥8.2「导入 Prometheus 规则」 |
| `alert-rules.n9e.json` | 兼容旧版：业务组「导入」JSON 数组（`prom_ql` / `severity`） |

## 导入步骤

### A. Prometheus YAML（推荐，v8.2+）

1. 夜莺控制台 → **告警规则** → **导入** → 选 Prometheus 规则。
2. 粘贴 [`alert-rules.prom.yml`](./alert-rules.prom.yml) 全文（须以 `groups:` 开头）。
3. 选择 **VictoriaMetrics** 数据源（与 Categraf / 表级 import 同一集群）。
4. 导入后 **批量选中** → 更多操作 → 绑定**通知规则**（平台组 IM/工单；§30.4 分级）。
5. 确认规则未 `disabled`；可选先在 VM 上用同一 PromQL 做瞬间查询验证。

### B. n9e JSON（旧 API / 脚本）

```bash
# 示例：按现网夜莺地址与业务组 id 替换
curl -sS -X POST "https://<n9e>/api/n9e/busi-group/<bgid>/alert-rules/import" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d @alert-rules.n9e.json
```

导入后同样须挂通知规则；JSON 内 `notify_channels` 为空，避免误推未配置通道。

### C. 数据源前置

- VM 已有 `lh_table_storage_*`（配 `lh.lifecycle.vm-import-url` 并跑日批）。
- 桶级：`ops/categraf-minio` 或 MinIO 原生名 + 门户/规则侧统一为 `lh_bucket_storage_*`；无配额时导入 `capacity_overlay.prom`。
- 规则求值间隔建议 60s；日批指标的 `for` 已放宽，避免抖动。

## 现网状态（2026-09-23）

**未探测到夜莺 / VictoriaMetrics / Categraf。** 本目录为规则包 + 导入说明交付；配齐栈后按上文导入即可，无需改门户代码。

## 残留（诚实）

1. **现网无夜莺**：无法在生产验证触发与通知路由。
2. **现网无 VM / 日批未写点**：断流与 TTF 规则在空库上只会表现为「全仓 absent」类告警（导入后请先确认有 series 再开通知）。
3. **`lh_ws_storage_{used,quota}_bytes` 尚未由门户/旁路写入 VM**（配额仍在 `gov_ws_quota`）；`LhWorkspaceStorageQuotaOver80Pct` 为契约占位，触发依赖后续投影作业（波次 G / 工作空间 showback）。桶水位 80% 规则可独立先用。
4. **告警挂表 owner / 桶 owner**：规则暂 `route=platform`；目录认责标签落地后改路由（生命周期待办 §2.5）。
5. **未接 Grafana 看板 / 基础设施磁盘深链**（§2.7 其它项，非 C4 最小闭环）。

## 明确不做

- 门户内第二套告警引擎
- Pushgateway
- 在规则里写死具体 webhook / 电话号码
