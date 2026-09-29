# Categraf · 节点 / 容器（L0 / L1）→ VictoriaMetrics

> 配套：§30 基础设施 · `LhObsInfraMetricsSupport`（读 `node_*` / `container_*`）· `deploy/obs-dev3`

## 指标契约（门户已查）

| 层 | PromQL 族 | 用途 |
|----|-----------|------|
| L0 | `node_cpu_seconds_total` / `node_memory_*` / `node_filesystem_*` / `node_network_*` | `/infra` 节点表 |
| L1 | `container_cpu_usage_seconds_total` / `container_memory_working_set_bytes` | `/infra` 容器表 |
| L3 | `up{job=...}` | 进程表（可选） |

## 文件

| 文件 | 说明 |
|------|------|
| `conf/config.toml` | 全局：写 VM remote_write |
| `conf/input.cpu/` 等 | 可直接用 Categraf 内置 `cpu`/`mem`/`disk`/`net`，或挂 node_exporter |
| `conf/input.prometheus/cadvisor.toml` | 抓 cAdvisor `:8080/metrics`（有则 L1 有数） |
| `conf/input.prometheus/node_exporter.toml` | 可选：已有 node_exporter 时 scrape |

## 部署（dev3 与桶采集同机）

```bash
# 在 categraf 配置目录增加本目录 conf 片段后重启
docker restart lh-categraf
# 冒烟
curl -sS 'http://127.0.0.1:8428/api/v1/query?query=count(node_cpu_seconds_total)'
curl -sS 'http://127.0.0.1:8428/api/v1/query?query=count(container_cpu_usage_seconds_total)'
```

门户：`GET /lh/observability/infra/nodes` 的 `source=vm:node_exporter` 即表示 L0 已通。

## 说明

- 抓取间隔建议 ≥15s（§30.2），勿压垮小规格节点。
- 无 node/cAdvisor 时门户**空态合法**，不会造假节点。
