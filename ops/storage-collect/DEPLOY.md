# 存储趋势采集栈部署（波次 K / D1）

> 目标：表级日批写 VM、桶级 Categraf → VM、夜莺读同批指标。门户只读。

## 1. VictoriaMetrics

```bash
cd lakehouse-gov/ops/victoria-metrics
docker compose up -d
# 或现网集群：确认保留 ≥400d，开放 import 给门户作业身份
```

配置门户：

```properties
lh.lifecycle.vm-import-url=http://<vm-host>:8428
# 可选：日批定时
# lh.lifecycle.profile-daily-enabled=true
```

## 2. Categraf MinIO（桶级）

按 [`ops/categraf-minio/README.md`](../categraf-minio/README.md)：

1. 部署 `input.prometheus/minio_bucket.toml`（interval ≥900s）
2. 可选 `rename_to_lh.example.toml` 投影为 `lh_bucket_storage_*`
3. 无桶配额时导入 `capacity_overlay.prom` 或填 `lh.lifecycle.bucket-capacity-bytes`

## 3. 夜莺规则

按 [`ops/nightingale-storage/README.md`](../nightingale-storage/README.md) 导入 `alert-rules.prom.yml`，数据源指向同一 VM：

- 表级 / 空间配额：`route=owner` → 按标签 `owner` 找人（`unassigned` 回落平台组）
- 桶级 / 全仓 absent：`route=platform` → 平台组

## 4. 冒烟清单

| # | 步骤 | 期望 |
|---|------|------|
| 1 | `curl $VM/health` | 200 |
| 2 | 跑 `smoke-vm.sh` 或 `smoke-vm.ps1` | import + query 看到 `lh_smoke_*` |
| 3 | `POST /lh/lifecycle/storage/collect/rerun` | `vm.ok=true`（非 skipped）；含 `lh_ws_storage_*` |
| 4 | VM query `lh_table_storage_bytes` | 有点且带 `owner` 标签 |
| 5 | `GET /lh/lifecycle/storage/buckets` | 顶层 `source` 以 `vm:` 开头（有点=`vm:lh_bucket_*`；无点=`vm:empty`） |
| 6 | 夜莺导入 `alert-rules.prom.yml` + 按 `notify-routes.example.yml` 绑 `route=owner`/`platform` | 通知分流 |
| 7 | 主台 `/lifecycle?focus=archive` · 出湖 `/export?focus=expire` | 互跳文案正确 |
| 8 | 一键脚本 | `ops/storage-collect/smoke-storage-acceptance.ps1 -VmUrl … -PortalUrl …` |

## 5. VM 写入权限与限流（§2.7）

- 门户 → VM：仅允许作业网段访问 `:8428`；生产建议反向代理 + 基本鉴权
- 日批与 Categraf 共用 ingest：日切一次批量 import，避免高频小包
- 失败隔离：`collect_status=failed` 的表**不写**趋势点（已实现）

## 6. `gov_lc_table_stat` 下线条件（D2）

同时满足后再下线种子表、改只读 API 纯读 VM：

1. `vm-import-url` 已配且连续 ≥15 天日批成功写点
2. `GET .../storage/summary` 的 `source` 以 VM 指标为主（非 seed）
3. 夜莺断流告警已挂通且误报可接受
4. 文档与兼容 `top-storage` 回归通过

未满足前：`gov_lc_table_stat` 继续作 P0 种子与降级。

## 7. 表枚举（D2）

采集目标顺序（`GovLcStorageProfileCollector`）：

1. 显式 `only` 列表（事件采集）
2. Trino `system.iceberg_tables`（若版本支持）
3. Gravitino Catalog 清单（metalake/catalog 下列表）
4. 回退：本空间 `gov_lc_policy` ∪ `gov_lc_table_stat`
