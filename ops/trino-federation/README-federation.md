# Trino 联邦源挂载（B6）

> 本目录为**可版本化副本**；运维权威工作副本亦见仓库根 `deploy/trino/`（与 impersonation 脚本同级）。  
> 配套文档：`doc/即席查询.md` §4.1 · `doc/部署台账.md` · 门户 `GET|POST .../catalog-map`。

## 验收

1. `SHOW CATALOGS` 含 `clickhouse`（可选 `tpch`）
2. `lh.trino.query-catalogs` 含 `clickhouse`（交付默认 `iceberg,clickhouse`）
3. `cb_trino_catalog_map` 启用（Flyway 种子自映射）
4. 样例：`SHOW SCHEMAS FROM clickhouse` 或 `SELECT 1`

## 文件

| 文件 | 说明 |
|------|------|
| `catalog/clickhouse.properties` | ClickHouse 联邦 connector → `lh-clickhouse:8123` |
| `catalog/tpch.properties` | 可选冒烟 |
| `apply-federation.sh` | 本机拷贝 + compose recreate |
| `apply-federation-remote.sh` | SSH 推 dev3（口令失败即停） |
| `verify-federation.sh` | Trino HTTP 探针 |

现网：把 `catalog/*.properties` 放入 `/opt/softs/lakehouse/trino/catalog/` 后 `./ctl.sh restart trino`。

## 现网状态（2026-09-23）

已挂载并验证：`SHOW CATALOGS` → `ck, clickhouse, iceberg, system, tpch`；`SHOW SCHEMAS FROM clickhouse` FINISHED。
门户白名单/Flyway V36 随 gov 发版后查询面才含 `clickhouse`。
