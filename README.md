# lakehouse-gov

湖仓一体数据治理平台 **后端**（基于 Snowy / Spring Boot 3）。对外 API 前缀 `/lakehouse`，湖仓能力主要在插件 `snowy-plugin-lh`。


## 模块

| 路径 | 说明 |
|------|------|
| `snowy-web-app` | 启动入口与 `application*.properties` |
| `snowy-plugin/snowy-plugin-lh` | 湖仓治理业务（数据源、资产、即席、生命周期、合规、AI 等） |
| `snowy-plugin-api/snowy-plugin-lh-api` | LH 模块对外 API 契约 |
| `snowy-common` / 其他 `snowy-plugin-*` | 通用与 Snowy 基座能力 |
| `ops/` | 联调与部署辅助（联邦 catalog 模板、监控规则包等） |
| `scripts/` | 工程脚本（如 Flyway 版本校验） |

前端门户为独立仓库（湖仓门户），本仓 `snowy-admin-web` 为 Snowy 管理端，可按需使用。

## 环境要求

- JDK 17+
- Maven 3.8+
- MySQL（业务库 `lakehouse_gov`）+ Redis
- 本机：主配置默认 `spring.profiles.active=local`（加载 gitignore 的 `application-local.properties`）

## 快速启动（本机）

1. 复制本机密钥文件（**勿提交**）：

```bash
cp snowy-web-app/src/main/resources/application-local.properties.example \
   snowy-web-app/src/main/resources/application-local.properties
```

在 `application-local.properties` 中填写真值（库口令、组件 bootstrap 等）。

2. 编译并启动（无需再手动指定 profile）：

```bash
mvn -pl snowy-web-app -am package -DskipTests
# 或在 IDE 直接运行 snowy-web-app 主类
```

默认：`http://localhost:8080/lakehouse`

## 多环境配置（MUST）

可提交配置 **禁止** 写口令/Token/Key 真值。约定如下：

| 文件 | 可提交 | 用途 |
|------|--------|------|
| `application.properties` | 是 | 共享非密钥默认；密钥键留空；默认 `spring.profiles.active=local` |
| `application-local.properties` | **否**（gitignore） | 本机开发，允许明文 |
| `application-local.properties.example` | 是 | local 模板 |
| `application-test.properties` | 是 | test：仅 `${LH_*}` |
| `application-prod.properties` | 是 | prod：仅 `${LH_*}` |
| `application-docker.yml` | 是 | 容器：`${MYSQL_PWD:}` / `${REDIS_PWD:}` 等 |

- test / prod：部署时 `-Dspring.profiles.active=test|prod` 或 `SPRING_PROFILES_ACTIVE` 覆盖，由环境变量注入密钥。
- 运行时凭证优先 Vault（`vault-path` → `ig_secret_store`）；yml/properties 明文仅作本机或首次 bootstrap。
- Trino 联邦含口令的 `ops/trino-federation/catalog/clickhouse.properties`、`ck.properties` 已 gitignore，只提交 `*.example`。


## 数据库迁移

Flyway 脚本**唯一目录**：`snowy-web-app/src/main/resources/db/migration/`。  
勿在 `snowy-plugin-lh/.../db/migration/` 放 `V*__*.sql`（与主包同 classpath 路径会撞版本号）。见 `doc/命名与工程约束.md` §6.0。

新增迁移前请先跑版本占用检查（多 Agent 并行时必须）：

```bash
python scripts/check-flyway-versions.py
```


## 相关目录速查

```text
snowy-web-app/src/main/resources/     # Spring 配置
snowy-plugin/snowy-plugin-lh/         # 湖仓业务代码 + application-lh.yml
ops/trino-federation/                 # Trino 联邦 catalog 模板
ops/litellm/ · ops/milvus/            # AI 联调参考
```

## License

继承上游 Snowy 的 Apache-2.0 许可（见 `LICENSE`）。本仓业务扩展归项目方维护。
