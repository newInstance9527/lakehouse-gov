# VictoriaMetrics（存储趋势时序 SoT）

> 配套：[`ops/storage-collect/DEPLOY.md`](../storage-collect/DEPLOY.md) · [`ops/categraf-minio`](../categraf-minio) · [`ops/nightingale-storage`](../nightingale-storage) · `doc/存储趋势.md` §5.2

## 本地 / 联调

```bash
cd lakehouse-gov/ops/victoria-metrics
docker compose up -d
curl -sS http://127.0.0.1:8428/health
```

门户配置（`application.properties` / `application-lh.yml`）：

```properties
# 本地联调
lh.lifecycle.vm-import-url=http://127.0.0.1:8428
# 现网（dev1）
# lh.lifecycle.vm-import-url=http://127.0.0.1:8428
```

与 DB 同机部署时务必加 `-memory.allowedBytes=512MB` / `mem_limit: 768m`（默认会按主机 60% 规划缓存）。现网说明见 [`deploy/victoria-metrics-dev1/README.md`](../../../deploy/victoria-metrics-dev1/README.md)。

写入路径：`POST {vm-import-url}/api/v1/import/prometheus`（门户 `VictoriaMetricsClient`，**不引 Pushgateway**）。

## 保留与写入约束（D2）

| 项 | 约定 |
|----|------|
| 保留 | **400 天**（compose `-retentionPeriod=400d`；支撑同比 + 90d 窗口） |
| 基数 | ≤200 表时约 2400 series；表数破千需降标签维度 |
| Staleness | 表删除/改名后**停写**，靠 absent 消失；**禁止**补 0 值 |
| 限流 | 日批一次写入；`maxInsertRequestSize=32MB`；与 Categraf 同集群时勿压垮 ingest |
| ACL | 生产应对 import API 做网络隔离或前置鉴权（VM 单节点默认无 auth） |

## 冒烟

见 [`../storage-collect/smoke-vm.sh`](../storage-collect/smoke-vm.sh) / `smoke-vm.ps1`。

## 现网

配好 URL 并跑 `POST /lh/lifecycle/storage/collect/rerun` 后，`lh_table_storage_*` 应有当日 `00:00 UTC` 点；桶级依赖 Categraf（见 `ops/categraf-minio`）。
