# 观测点亮切流清单（波次 O3）

> 配套：`ops/storage-collect` · `ops/categraf-node` · `ops/nightingale-*` · 门户 `lh.observability.*`

## 目标

内网试点可在「基础设施 / 存储趋势 / 运维」页看到真实点位，空态仅表示「尚未接入」而非假数据。

## 步骤

1. **VictoriaMetrics**  
   - 确认 `:8428` 可达；门户 `lh.lifecycle.vm-import-url` / `lh.observability` 填入  
2. **Categraf**  
   - node_exporter / MinIO / 关键容器按 `ops/categraf-node` · `ops/categraf-minio` 启  
   - 验收：`up` / `node_*` / MinIO 指标有点  
3. **夜莺**  
   - `ops/nightingale-*/import-and-smoke.sh`  
   - 门户 `lh.observability.nightingale-*`  
4. **门户冒烟**  
   - `/infra` 有节点卡片  
   - `/lifecycle/storage` 有桶趋势（有仓则非空）  
   - `/ops` 告警列表可读夜莺当前告警（可空）

## 勾选

- [x] VM 有 `up` 系列（2026-09-30：`count(up)=4`；`node_*` / MinIO 有点）  
- [x] Infra 页数据源侧：`node_cpu_seconds_total` / `node_memory_MemAvailable_bytes` 已有点（dev3 起 `lh-node-exporter:9100`；错误 cadvisor→ui-auth:8080 刮取已 disable）  
- [x] 夜莺导入：`alert-rules.n9e.json` HTTP 200（规则已存在，幂等）  

