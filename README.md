# lakehouse-gov · 湖仓一体数据治理平台（后端）

基于 **Snowy / Spring Boot 3** 的后端。HTTP 上下文 **`/lakehouse`**；湖仓业务集中在插件 **`snowy-plugin-lh`**（路径前缀 **`/lh/*`**）。认证与系统管理沿用 Snowy（`/auth/*`、`/sys/*`）。

| 项 | 说明 |
|----|------|
| 前端仓 | `lakehouse`（Vue 门户） |
| 设计文档 | 同工作区 `doc/` |
| API 文档 | 启动后 `http://localhost:8080/lakehouse/doc.html`（Knife4j） |

---

## 1. 快速启动

### 环境

| 项 | 要求 |
|----|------|
| JDK | **17+** |
| Maven | **3.8+** |
| MySQL | 业务库 `lakehouse_gov` |
| Redis | 会话 / 缓存 |
| 可选组件 | Trino、Vault、DolphinScheduler、Gravitino、OpenMetadata、Gitea 等（按功能启用） |

### 本机步骤

1. **复制本机密钥文件（勿提交）**

```bash
cp snowy-web-app/src/main/resources/application-local.properties.example \
   snowy-web-app/src/main/resources/application-local.properties
```

在 `application-local.properties` 中填写真库口令、组件 bootstrap 等。

2. **编译并启动**

```bash
# 打包（推荐先过一遍）
mvn -pl snowy-web-app -am package -DskipTests

# 或直接跑
mvn -pl snowy-web-app -am spring-boot:run
```

IDE：运行主类 `vip.xiaonuo.Application`（模块 `snowy-web-app`）。

默认：

- 地址：`http://localhost:8080/lakehouse`
- Profile：`spring.profiles.active=local`（见 `application.properties`）
- Flyway：启动时自动迁移 `classpath:db/migration`

test / prod：

```bash
mvn -pl snowy-web-app -am spring-boot:run -Dspring-boot.run.profiles=prod
# 或环境变量 SPRING_PROFILES_ACTIVE=prod，密钥用 LH_* 注入
```

---

## 2. 架构

```
                    ┌─────────────────────────────────────┐
  门户 / 网关 ─────►│ snowy-web-app  (:8080/lakehouse)     │
                    │  Application · 全局配置 · Flyway     │
                    └──────────────┬──────────────────────┘
                                   │ 装载插件
          ┌────────────────────────┼────────────────────────┐
          ▼                        ▼                        ▼
   snowy-plugin-auth         snowy-plugin-sys         snowy-plugin-lh
   /auth/b/*                 /sys/*                   /lh/*
   Sa-Token 登录             用户组织菜单               湖仓治理域
                                   │
                                   ▼
                    MySQL(lakehouse_gov) · Redis · Vault
                                   │
              Trino · DS · Flink/Spark/DataX · Grav · OM · Gitea · …
```

**分层（LH 插件内典型路径）：**

```
controller  →  service / impl  →  mapper(entity)
                 │
                 ├─ support/     探数、投影、门禁、Outbox 等
                 └─ job/         定时任务
```

**配置分层：**

| 文件 | 可提交 | 用途 |
|------|--------|------|
| `application.properties` | 是 | 共享非敏感默认；**密钥与组件主机/URL 留空**；默认 `local` |
| `application-local.properties` | **否** | 本机明文：口令 + JDBC/Redis/组件 URL |
| `application-local.properties.example` | 是 | local 模板（示例用 `127.0.0.1`） |
| `application-test|prod.properties` | 是 | 口令与 URL 均 `${LH_*}` |
| `application-docker.yml` | 是 | 容器变量 |
| `application-lh.yml`（插件） | 是 | `lh.*` 非敏感默认；URL 留空，由 local/ENV 覆盖 |

运行时凭证优先 **Vault**（`vault-path` → `ig_secret_store`）；properties 明文仅本机或首次 bootstrap。

---

## 3. 代码结构

### 3.1 Maven 模块

| 路径 | 说明 |
|------|------|
| `snowy-web-app` | 启动入口、`application*`、**Flyway 唯一目录** |
| `snowy-plugin/snowy-plugin-lh` | 湖仓业务实现 |
| `snowy-plugin-api/snowy-plugin-lh-api` | LH 对外 API 契约 |
| `snowy-plugin/snowy-plugin-auth` 等 | Snowy 基座（认证、系统、业务扩展） |
| `snowy-common` | 通用工具与基类 |
| `ops/` | 联邦 catalog、夜莺规则、AI/备份联调辅助 |
| `snowy-admin-web/` | 上游管理端（可选，不在默认 reactor 强依赖） |

### 3.2 Flyway（MUST）

**唯一脚本目录：**

```text
snowy-web-app/src/main/resources/db/migration/
```

**禁止**在 `snowy-plugin-lh/.../db/migration/` 新增 `V*__*.sql`（classpath 合并会撞版本）。  
多 Agent 并行写迁移前，在工作区根执行：

```bash
python scripts/check-flyway-versions.py
```

并按 `.cursor/rules` / `docs/agent-state` 约定占用版本号。

### 3.3 LH 插件目录

```text
snowy-plugin/snowy-plugin-lh/src/main/java/vip/xiaonuo/lh/
├── config/                 # LhProperties 等
├── core/engine/            # TrinoClient、Gravitino、DS 脚本构建等
└── modular/                # 按业务域分包（见下节）
```

每个 `modular/<域>/` 通常含：`controller` · `service` · `entity` · `mapper` · `param` · `support`。

---

## 4. 功能模块（`modular/*`）

| 包 | API 前缀（示意） | 职责 |
|----|------------------|------|
| `domain` | `/lh/domain` | 数据域 SoT |
| `standard` | `/lh/standard` | 数据标准 / 码值 / 检测结果 |
| `contract` | `/lh/contract` | 数据契约 |
| `datasource` | `/lh/datasource` | 数据源登记、探测、Vault、Grav 投影 |
| `etl` | `/lh/etl` | ETL 编排、试跑/发布、质量节点联动 |
| `export` | `/lh/export` | 出湖与回流运营 |
| `catalog` | `/lh/catalog` | 资产目录、质量分回写 |
| `lineage` | `/lh/lineage` | 字段血缘 |
| `lifecycle` | `/lh/lifecycle` | 冷热分层、Iceberg 维护、存储趋势 |
| `compliance` | `/lh/compliance`（del） | 合规删除、限制处理、SLA |
| `compute` | `/lh/compute` | 脚本开发、Gitea SoT、发布门禁 |
| `query` | `/lh/compute/query` 等 | Trino 即席闸门、审计 |
| `quality` | `/lh/quality` | 规则、evaluate 真探数、门禁、runs |
| `sec` | `/lh/sec` | 授权、Trino 主体映射 |
| `apply` | `/lh/apply` | 申请中心工单 |
| `metric` | `/lh/metric` | 指标口径与物化相关 |
| `dataapi` | `/lh/dataapi` | 数据服务绑定、密钥、运行时鉴权 |
| `workspace` | `/lh/workspace` | 工作空间隔离与配额 |
| `ai` / `aimodel` / `knowledge` | `/lh/ai*` 等 | 助手、模型、知识库 |
| `observability` | `/lh/obs*` 等 | 链路、成本、夜莺推送 |
| `recon` | `/lh/recon` | 可靠性对账 / 黄金事件 |
| `engine` | `/lh/engine` | 外部引擎运维探活 |
| `schemasync` | — | Grav → OM schema 同步 |
| `plat` | — | Outbox 异步投递 |
| `org` | — | 非系统人员等组织扩展 |
| `moduleops` | — | 模块连通性探活 |

门户登录与菜单仍走 Snowy：`/auth/b/*`、`/sys/*`。

### 外部依赖（按需）

Vault · Trino · DolphinScheduler · Flink / Spark / DataX · Gravitino · OpenMetadata · Marquez · Gitea · ClickHouse · SQLREST / APISIX · MinIO · VictoriaMetrics · 夜莺 · LiteLLM · Milvus 等。连接串与密钥放 Vault 或 local overlay，**勿写入可提交配置真值**。

---

## 5. 联调与辅助

| 用途 | 位置 |
|------|------|
| Flyway 版本占用检查 | 工作区 `scripts/check-flyway-versions.py` |
| 标准 / 契约接口种子 | `scripts/seed-*-via-api.mjs` |
| 发布门禁本机校验 | `scripts/verify-release-gates-local.mjs` |
| Trino 联邦模板 | `ops/trino-federation/`（含口令文件已 gitignore，提交 `*.example`） |
| AI / 监控参考 | `ops/litellm/` · `ops/milvus/` · `ops/nightingale-*` |

本地放宽发布门禁示例（已写入 `application-local.properties.example`）：

```properties
lh.compute.require-mr-merge=false
lh.compute.require-script-publish-ticket=false
lh.compute.publish-gate-hard-fail=false
```

prod 保持默认硬门禁。

---

## 6. 与前端协作

| 前端 | 后端 |
|------|------|
| `VITE_API_BASE=/lakehouse` | `server.servlet.context-path=/lakehouse` |
| `POST .../auth/b/doLogin`（SM2） | Sa-Token，头字段 `token` |
| `src/api/*.js` → `/lh/...` | `modular/*/controller` |
| 开发代理 `5173` → `8080` | 本机 `spring-boot:run` / IDE |

更细的模块说明、SoT 边界见工作区 `doc/`（如数据质量、资产目录、ETL 编排等）。

---

## License

继承上游 Snowy 的 Apache-2.0（见 `LICENSE`）。本仓湖仓业务扩展归项目方维护。
