# AI 平台 · LiteLLM 生产包 + Vault Key + 巡检（D1）+ 别名同步（D3）

> 配套：`doc/AI模型管理.md` · `doc/AI平台能力-部署说明.md` · `doc/AI平台能力-跨模块待办.md`  
> 门户配置：`lh.ai.*`（`application.properties`）  
> Vault 路径约定：`secret/lakehouse/ai/{modelId}`（`LhVaultPaths.aiModel`）

## 验收清单

| # | 条件 | 现网有 LiteLLM 时 | 本交付（现网未部署） |
|---|------|-------------------|----------------------|
| 1 | LiteLLM Proxy 可达 | `/health*` 或 `/v1/models` 200 | compose + config 可起 |
| 2 | 门户 `lh.ai.enabled=true` + `litellm-url` | Copilot / test 走真网关 | 配置项与接线说明已写 |
| 3 | 创建/轮换 Key → Vault；列表仅脱敏 | `POST .../models` / `.../rotate` | API + `ig_secret_store` 已落地 |
| 4 | 巡检：连通 + Vault 缺 Key + 过期预警 | `POST /lh/ai/models/patrol` 或定时任务 | 代码 + `patrol-models.sh` |
| 5 | 厂商 Key **不进 Git** | 仅 env / Vault Agent | `.env.example` 占位 |
| 6 | 别名/启停同步（D3） | `lh/{id}` upsert + block；路由 fallbacks | 代码软降级 + 探针；现网未起则 skipped |

## 拓扑

```
门户 /lh/ai/*  ──内网──►  LiteLLM :4000  ──► 厂商 / 内网 vLLM
       │                      ▲
       │                      │ env 注入（Vault Agent 或手工）
       │                      │ D3：/model/new|update|block + /config/update fallbacks
       └─ Key SoT: ig_secret_store(vault_path)
       └─ 登记 SoT: gov_ai_model（启停先写库，再软同步网关）
```

门户**不**把 Vault 明文塞进 LiteLLM 请求头做厂商鉴权；网关侧 Key 由运维按 `config.yaml` 的 `api_key: os.environ/...` 注入。门户 Vault 保证：登记/轮换有据、列表脱敏、巡检可验「平台侧是否有 Key」。

**D3 别名**：门户同步写入 `model_name=lh/{modelId}`；`api_key` 仅 `os.environ/...` 占位。未启用 `lh.ai` / 网关不可达 / 管理 API 失败 → **软跳过**，门户 CRUD/启停仍成功。

## 文件

| 文件 | 用途 |
|------|------|
| `docker-compose.yml` | 单节点 LiteLLM Proxy |
| `config.yaml` | 模型清单模板（静态种子；热同步见下） |
| `.env.example` | `LITELLM_MASTER_KEY` / 厂商 Key 占位 |
| `verify-litellm.sh` | 网关健康探测 |
| `patrol-models.sh` | 调门户 `POST /lh/ai/models/patrol`（需登录 Token） |

旁路镜像：仓库根 `deploy/litellm/`（与 `ops/litellm` 同内容，便于现网拷贝）。

## 启动（示例）

```bash
cd ops/litellm   # 或 deploy/litellm
cp .env.example .env
# 编辑 .env：填 LITELLM_MASTER_KEY 与至少一个厂商 Key
docker compose up -d
./verify-litellm.sh
```

### 热同步（D3，可选）

LiteLLM 的 `/model/new` 等管理 API 通常需要 `STORE_MODEL_IN_DB=True` + 网关侧 DB。  
本 compose **默认不带** DB（保持单节点可起）：

- **无 DB**：门户仍登记；同步返回 `skipped`/`ok=false`；运维用 `config.yaml` 静态别名对齐。
- **有 DB**：在网关 env 打开 `STORE_MODEL_IN_DB` 并挂 Prisma/Postgres 后，门户启停即可 live 同步。

门户：

```properties
lh.ai.enabled=true
lh.ai.litellm-url=http://litellm:4000
lh.ai.litellm-master-key=${与 .env LITELLM_MASTER_KEY 一致}
lh.ai.default-chat-model=gpt-4o-mini
lh.ai.default-embed-model=text-embedding-3-small
lh.ai.patrol-enabled=true
lh.ai.patrol-cron=0 0 * * * ?
lh.ai.patrol-warn-days=14
```

手动巡检：

```bash
# 需门户 Bearer；也可用前端「测试」对单模型
./patrol-models.sh https://portal.example.com '<token>'
```

同步探针：

```bash
curl -sS -H "token: <门户token>" \
  'https://portal.example.com/lakehouse/lh/ai/models/gateway/probe'
```

## API（D1 + D3）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/lh/ai/models/{id}/rotate` | Key → Vault；响应仅 `keyMask` |
| POST | `/lh/ai/models/patrol` | LiteLLM health + 启用模型连通 + Key 过期 |
| POST | `/lh/ai/models/{id}/test` | 单模型；优先别名 `lh/{id}`，回退 `modelName` |
| POST | `/lh/ai/models/{id}/enable` | 启停写库 + 软同步 LiteLLM（响应含 `litellmSync*`） |
| GET | `/lh/ai/models/gateway/probe` | D3：enabled / reachable / syncCapable / mode |
| GET | `/lh/ai/models/overview` | 增补 `litellmEnabled` / `litellmSyncMode` 等 |

列表/详情 **禁止**回明文 Key（仅 `keyMask` / `vaultPath`）。

## 现网状态（2026-09-23）

**未探测到生产 LiteLLM 进程；门户 `lh.ai.enabled` 默认 false。**  
本目录为可部署配置 + 巡检脚本 + 门户接线（含 D3 同步软降级）交付；起栈并打开开关后即可验收 live 路径。

## 残留（诚实）

1. **现网未部署 LiteLLM**：无法在生产验证 chat/embed 真连通与 live 别名同步。
2. **热同步依赖网关 `store_model_in_db`**：默认 compose 无 DB 时同步软失败，属预期降级。
3. **Milvus**：D2 已交付 `ops/milvus`；现网未起栈仍为残留。
4. **巡检失败 / Key 过期 → 夜莺**（§2.12）未接；本波次只写 `gov_ai_model.status`。
5. **厂商 Key 从门户 Vault 自动投影到 LiteLLM env** 未做（需 Vault Agent / 旁路；禁止脚本把明文写进 Git）。

## 明确不做

- 浏览器直连厂商 LLM
- 第二套 OneAPI 控制台替代门户模型页
- 把 API Key 明文写入 compose / config 并提交仓库
