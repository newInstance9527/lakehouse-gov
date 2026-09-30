# §4.2 组件口令轮换 checklist（波次 O4）

> SoT：[命名与工程约束.md](../../../doc/命名与工程约束.md) §4.2  
> 规则：新值只进密码器 / Vault / 本机 `application-local.properties`，**禁止**写回可提交文件。

## 执行勾选

- [ ] MySQL master 口令  
- [ ] Redis  
- [ ] Trino / DS / Flink / ClickHouse  
- [ ] SQLREST / OM / Grav / Marquez / Superset  
- [ ] MinIO AK/SK  
- [ ] Gitea token / LiteLLM master key  
- [ ] 合规 HMAC / Vault AES key（若仍用 aes）  
- [ ] 门户与各组件配置同步后冒烟（登录 / 测连 / 即席 SELECT 1）  
- [ ] （建议）Git 历史 `filter-repo` 清密后强制换 clone

完成日期：________  执行人：________

> **O+2（2026-09-30）**：本轮 **未执行轮换**（需变更窗 + 组件联动冒烟）。现口令可达性已核验：MySQL dump/restore、夜莺 login、SSH 运维账号。轮换仍按上表逐项改密后勾选。

