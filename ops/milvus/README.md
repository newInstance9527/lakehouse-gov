# AI 平台 · Milvus 向量部署包（D2）

> 配套：`doc/知识库.md` · `doc/AI平台能力-部署说明.md` · `doc/AI平台能力-跨模块待办.md`  
> 门户配置：`lh.ai.milvus-*`（`application.properties`）  
> 客户端：`LhMilvusClient`（ensure / upsert / search / delete；失败软降级关键词）

## 验收清单

| # | 条件 | 现网有 Milvus 时 | 本交付（现网未部署） |
|---|------|------------------|----------------------|
| 1 | Milvus standalone 可达 | `:9091/healthz` 200；gRPC `:19530` | compose + `verify-milvus.sh` 可起 |
| 2 | 门户 `milvus-enabled=true` + URI | 新建知识 upsert 向量；search 可出 `vecScore` | 配置项 + `vector/probe` 已写 |
| 3 | `milvus-enabled=false` 或不可达 | CRUD / 关键词 search 仍可用 | 客户端软失败 + 关键词路径保留 |
| 4 | 集合字段约定 | `chunk_id`/`entry_id`/`ws`/`cat`/`embedding` | 首次 `ensureCollection` 自动建 |
| 5 | 第二套向量引擎 | ❌ 不预留 | 唯一选型 Milvus |

## 拓扑

```
门户 /lh/knowledge/*  ──内网──►  Milvus :19530 (gRPC)
       │                            ▲
       │                            │ etcd + MinIO（compose 内）
       └─ 元数据 SoT: MySQL gov_kb_*
```

检索：`milvus-enabled` 且连接成功且 embed 成功 → 混合（向量优先 + 关键词补齐）；否则 **仅 MySQL 关键词**。CRUD 不依赖 Milvus。

## 文件

| 文件 | 用途 |
|------|------|
| `docker-compose.yml` | etcd + MinIO + Milvus standalone |
| `.env.example` | 端口 / MinIO 口令占位 |
| `verify-milvus.sh` | HTTP healthz + gRPC 端口探活 |
| `probe-vector.sh` | 调门户 `GET /lh/knowledge/vector/probe` |

旁路镜像：仓库根 `deploy/milvus/`（与 `ops/milvus` 同内容，便于现网拷贝）。

## 启动（示例）

```bash
cd ops/milvus   # 或 deploy/milvus
cp .env.example .env
docker compose up -d
./verify-milvus.sh
```

门户：

```properties
lh.ai.milvus-enabled=true
lh.ai.milvus-uri=http://milvus:19530
# lh.ai.milvus-token=          # Zilliz Cloud 等
lh.ai.milvus-database=default
lh.ai.milvus-collection=lh_kb_chunk
lh.ai.embed-dim=1536
lh.ai.vector-weight=0.7
# 向量写入还需 LiteLLM embed（D1）；仅开 Milvus 无 embed 时仍关键词
lh.ai.enabled=true
lh.ai.litellm-url=http://litellm:4000
lh.ai.default-embed-model=text-embedding-3-small
```

联调探针：

```bash
./probe-vector.sh https://portal.example.com '<token>'
# 期望：enabled=true 且 reachable=true → mode=vector；关闭开关 → mode=keyword
```

## API（D2 增量）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/lh/knowledge/vector/probe` | `enabled` / `reachable` / `mode` / `collection` |
| GET | `/lh/knowledge/overview` | 增补 `milvusEnabled` / `milvusReachable` / `retrievalMode` |
| POST | `/lh/knowledge/search` | 命中项带 `retrievalMode`=`keyword`\|`hybrid`；`vecScore` 有值即向量参与 |

## 现网状态（2026-09-23）

**未探测到生产 Milvus 进程；门户 `lh.ai.milvus-enabled` 默认 false。**  
本目录为可部署 compose + 探针脚本 + 门户接线（客户端 / 降级 / probe）交付；起栈并打开开关后即可验收向量路径，无需再改契约。

## 残留（诚实）

1. **现网未部署 Milvus / LiteLLM**：无法在生产验证 upsert + 向量 search 真连通。
2. **Embedding 依赖 D1 LiteLLM**：仅起 Milvus、无 embed 模型时仍走关键词（预期）。
3. **LiteLLM 别名同步 / 启停联动** → 波次 **D3**。
4. **合规删除同步清 Milvus 向量** → 合规分册开放项。

## 明确不做

- 第二套向量引擎（pgvector / Qdrant / Weaviate 等）
- 浏览器直连 Milvus
- 把 MinIO/Milvus 口令明文写进 Git（仅 `.env.example` 占位）
