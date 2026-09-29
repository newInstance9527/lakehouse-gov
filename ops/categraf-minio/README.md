# Categraf MinIO 桶指标 → VictoriaMetrics（C3）

> 配套：`doc/存储趋势.md` §4.2 / §5.2 · `doc/存储趋势-跨模块待办.md` §2.2 / §2.7 · 门户
> `GET /lh/lifecycle/storage/buckets` · `GET /lh/lifecycle/storage/trend?group=bucket`。
>
> **不重复采集**：只抓 MinIO 已有 metrics 端点，写入 VM；门户只读。

## 验收（现网有 Categraf/VM 时）

1. Categraf 按 **≥15min**（默认 900s）抓 MinIO bucket 端点，不反压（全栈 §30.2）
2. VM 中可见任一：
   - 平台名：`lh_bucket_storage_used_bytes` / `lh_bucket_storage_object_count` /（可选）`lh_bucket_storage_capacity_bytes`
   - 或原生：`minio_bucket_usage_total_bytes` / `minio_bucket_usage_object_total`（v2）或 v3 等价名
3. 配好 `lh.lifecycle.vm-import-url` 后，`GET .../storage/buckets` 的 `source` 以 `vm:` 开头；`trend?group=bucket` 的 series 与桶用量同源
4. days-to-full 容量优先读桶 capacity（VM 配额指标或 `lh.lifecycle.bucket-capacity-bytes`），不再只靠全局软上限

## 指标契约

| 平台指标 | 含义 | MinIO 回退（v2 / v3） |
|----------|------|----------------------|
| `lh_bucket_storage_used_bytes{bucket}` | 桶已用字节 | `minio_bucket_usage_total_bytes` / `minio_cluster_usage_buckets_total_bytes` |
| `lh_bucket_storage_object_count{bucket}` | 对象数 | `minio_bucket_usage_object_total` / `minio_cluster_usage_buckets_objects_count` |
| `lh_bucket_storage_capacity_bytes{bucket}` | 容量 | `minio_bucket_quota_total_bytes` / `minio_cluster_usage_buckets_quota_total_bytes`；**无配额时用门户 yaml 覆盖** |

门户查询用 MetricsQL：`platform or minio_v2 or minio_v3`，**禁止**门户再扫一遍 MinIO。

## capacity 来源（重要）

MinIO 默认常常**没有**桶级配额指标。容量优先级：

1. VM 中的 `lh_bucket_storage_capacity_bytes` / MinIO quota 指标
2. `lh.lifecycle.bucket-capacity-bytes`（`application-lh.yml` 已给演示桶默认值）
3. `lh.lifecycle.forecast-default-capacity-bytes`（全局软上限）

分层 → 桶：`lh.lifecycle.layer-bucket-map`（默认 ODS→iceberg-ods 等）。

可选：在 Categraf 侧用 `mapping` / 静态 labels 投影 capacity；本目录 `capacity_overlay.prom` 为示例文本，可经 vmagent/`/api/v1/import/prometheus` 导入（与表级日批同通道）。

## 文件

| 文件 | 说明 |
|------|------|
| `input.prometheus/minio_bucket.toml` | 抓 `/minio/v2/metrics/bucket`（+ cluster 可选），interval=900s |
| `rename_to_lh.example.toml` | 可选：把 MinIO 名 rename 成平台 `lh_bucket_storage_*` |
| `capacity_overlay.prom` | 可选：静态 capacity 样本（按现网桶名改） |

## 现网状态

联调可先起本地 VM：见 [`ops/victoria-metrics`](../victoria-metrics) 与一键部署清单 [`ops/storage-collect/DEPLOY.md`](../storage-collect/DEPLOY.md)。

**生产若未部署 Categraf**：桶级 `storage/buckets` / `trend?group=bucket` 为空态（禁止 seed）；days-to-full 容量仍可读 `lh.lifecycle.bucket-capacity-bytes` / 软上限。配好 Categraf→VM 后无需改门户代码即可切真桶水位。

**VM 已部署**：配 `lh.lifecycle.vm-import-url` 后表级日批直写 `lh_table_storage_*` / `lh_ws_storage_*`。

## VM 写入权限与限流

- Categraf remote_write / scrape → 同一 VM；门户日批走 `import/prometheus`，与本模块**分通道同库**
- 抓取间隔 ≥15min，禁止压垮 MinIO metrics 端点（全栈 §30.2）
- 生产对 VM `:8428` 做网段 ACL；冒烟脚本见 `ops/storage-collect/smoke-vm.*`
