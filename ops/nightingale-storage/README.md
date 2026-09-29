# 夜莺告警规则包 · 存储趋势 / 生命周期（C4）

> 配套：`doc/存储趋势.md` §4.4 / §5.2 · `doc/存储趋势-跨模块待办.md` §2.7 ·
> `doc/生命周期-跨模块待办.md` §2.5 · `ops/categraf-minio`（桶指标）· 表级日批 `lh_table_storage_*`。
>
> **不另建告警通道**：规则只读 VictoriaMetrics 中已有（或契约约定）指标。

## 验收清单

| # | 条件 | 现网有夜莺+VM 时 | 本交付 |
|---|------|------------------|--------|
| 1 | 桶 / 表 TTF &lt; 45d → 认责方 | 表：`route=owner`；桶：平台组 | 表达式与指标名对齐 |
| 2 | 配额 / 桶水位 ≥ 80% | 空间：`lh_ws_storage_*` + owner；桶：平台组 | 日批已投影空间配额 |
| 3 | 表级采集断流（24h 无点） | `route=owner` | PromQL 已写 |
| 4 | 小文件 &gt;30% / 环比恶化（compaction） | `route=owner` | 对齐 `lh_table_storage_small_file_ratio` |
| 5 | 与门户同源 | 同读 `lh_*`，无第二套引擎 | 契约表见下 |

## 指标对齐

| 规则意图 | PromQL 主指标 | 来源 |
|----------|---------------|------|
| 表 TTF &lt; 45 | `lh_table_storage_days_to_full{quantile="p95"}` | 日批写回（含 `owner` 标签） |
| 桶 TTF &lt; 45 | `lh_bucket_storage_used_bytes` / `capacity_bytes` 推算 | Categraf + capacity |
| 桶水位 ≥ 80% | `used / capacity` | 同上 |
| 空间配额 ≥ 80% | `lh_ws_storage_used_bytes` / `lh_ws_storage_quota_bytes` | 日批：`gov_lc_table_stat` + `gov_ws_quota` |
| 采集断流 | `lh_table_storage_bytes{kind="total"}` 的 `timestamp` / `absent_over_time` | 日批 |
| 小文件 / compaction | `lh_table_storage_small_file_ratio`、`avg_file_bytes` | 日批 |

标签约定：表级 `fqtn` / `ws` / `layer` / `owner`（空 → `unassigned`）；空间级 `ws` / `owner`；桶级 `bucket`。  
规则路由：`route=owner`（表/空间）或 `route=platform`（桶/全仓 absent）。

## 文件

| 文件 | 用途 |
|------|------|
| `alert-rules.prom.yml` | **首选**：Prometheus `groups` YAML，夜莺 ≥8.2「导入 Prometheus 规则」 |
| `alert-rules.n9e.json` | 兼容旧版：业务组「导入」JSON 数组（`prom_ql` / `severity`） |
| `notify-routes.example.yml` | 通知分流样例：`route=owner` / `route=platform` |

## 导入步骤

### A. Prometheus YAML（推荐，v8.2+）

1. 夜莺控制台 → **告警规则** → **导入** → 选 Prometheus 规则。
2. 粘贴 [`alert-rules.prom.yml`](./alert-rules.prom.yml) 全文（须以 `groups:` 开头）。
3. 选择 **VictoriaMetrics** 数据源（与 Categraf / 表级 import 同一集群）。
4. 导入后按 [`notify-routes.example.yml`](./notify-routes.example.yml) 建两条通知规则：
   - `route=owner`：按标签 `owner` 找人；`unassigned` 回落平台组
   - `route=platform`：平台组 IM/工单（桶 / 全仓 absent）
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

- VM 已有 `lh_table_storage_*`（配 `lh.lifecycle.vm-import-url` 并跑日批 / `collect/rerun`）。
- 日批同时写 `lh_ws_storage_{used,quota}_bytes`（配额 0 时空间规则因 `quota > 0` 不触发）。
- 桶级：`ops/categraf-minio` 或 MinIO 原生名 + 门户/规则侧统一为 `lh_bucket_storage_*`；无配额时导入 `capacity_overlay.prom`。
- 规则求值间隔建议 60s；日批指标的 `for` 已放宽，避免抖动。

## 现网状态

**VM + Categraf + 夜莺已部署**：按 [`ops/storage-collect/DEPLOY.md`](../storage-collect/DEPLOY.md) 冒烟；规则导入后为 `route=owner` 配「按 owner 找人 / unassigned→平台组」，桶级仍平台组。

本地联调亦可先起 [`ops/victoria-metrics`](../victoria-metrics)。

## 残留（诚实）

1. **通知通道未绑定时**：规则可求值但不推人；须在夜莺绑定 IM/工单。
2. **VM 无点时**：断流与 TTF 规则在空库上会偏「全仓 absent」（导入后请先确认有 series 再开通知）。
3. ~~`lh_ws_storage_*` 未投影~~：**已由日批投影**。
4. ~~表级告警仅平台组~~：**已 `route=owner`**；桶 owner 投影仍开放。
5. ~~Grafana 可选看板~~：**已明确不做**。
6. **基础设施磁盘深链**：门户 `InfraView` → `/lifecycle/storage?bucket=`。

## 明确不做

- 门户内第二套告警引擎
- Pushgateway
- 在规则里写死具体 webhook / 电话号码
