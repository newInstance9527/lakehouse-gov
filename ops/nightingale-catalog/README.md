# 夜莺 · 资产目录元数据漂移（H4）

指标由门户 `POST /lh/catalog/assets/drift/reconcile`（及 refresh 单资产挂接）写入 VictoriaMetrics：

- `lh_meta_drift_open{ws,severity,drift_type}`

规则文件：[`alert-rules.prom.yml`](./alert-rules.prom.yml)

部署镜像：`deploy/nightingale-catalog/`（与本目录同源）。

现网无夜莺/VM 时指标写入 soft-fail，漂移单仍落 `recon_meta_drift`，摘牌走门户 `gov_asset` + outbox。
