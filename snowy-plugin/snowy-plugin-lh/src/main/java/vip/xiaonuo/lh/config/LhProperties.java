/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.vault.LhVaultPaths;

/**
 * 湖仓外部组件配置
 * <p>运行时凭证优先从 {@code vault-path} 读取；yml 中的 user/password/token/apiKey
 * 仅作首次启动 bootstrap 种子，写入 {@code ig_secret_store} 后生产环境应清空明文。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "lh")
public class LhProperties {

    private Vault vault = new Vault();
    private Trino trino = new Trino();
    private Ds ds = new Ds();
    private Flink flink = new Flink();
    private Spark spark = new Spark();
    private Datax datax = new Datax();
    private Clickhouse clickhouse = new Clickhouse();
    private Sqlrest sqlrest = new Sqlrest();
    private Dataapi dataapi = new Dataapi();
    private Apisix apisix = new Apisix();
    private Superset superset = new Superset();
    private Minio minio = new Minio();
    private Openmetadata openmetadata = new Openmetadata();
    private Marquez marquez = new Marquez();
    private Gravitino gravitino = new Gravitino();
    private SchemaSync schemaSync = new SchemaSync();
    private Etl etl = new Etl();
    private Catalog catalog = new Catalog();
    private Ai ai = new Ai();
    private Lifecycle lifecycle = new Lifecycle();
    private Finops finops = new Finops();
    private Compute compute = new Compute();
    private Compliance compliance = new Compliance();
    private Metric metric = new Metric();
    private Quality quality = new Quality();
    private Export export = new Export();

    @Getter
    @Setter
    public static class Vault {
        /** AES 密钥材料（勿提交生产真实值到公共仓库） */
        private String aesKey = "";
    }

    @Getter
    @Setter
    public static class Trino {
        private String url;
        /** Trino Web UI 基址；空则同 url（去路径） */
        private String uiUrl;
        private String vaultPath = LhVaultPaths.TRINO;
        /** bootstrap only */
        private String user;
        private String password;
        private boolean insecureSsl = true;
        /**
         * true：人查用 X-Trino-User 代执行已映射主体（Trino 须允许服务账号 impersonate）。
         * false：人查直接拒绝，不会改用服务账号。
         */
        private boolean impersonate = true;
        /**
         * 即席查询 catalog 白名单（逗号分隔），与 SHOW CATALOGS 求交后才可跑。
         * 默认 iceberg + 联邦 clickhouse（须 Trino 已挂载；不是 Grav 的 ds_*）。
         */
        private String queryCatalogs = "iceberg,clickhouse";
        /**
         * 视为湖表的 Grav/Trino catalog（逗号分隔）。湖表查询 FQN 优先落在此集合中的名。
         */
        private String lakeCatalogs = "iceberg";
        /** SHOW CATALOGS 缓存毫秒；≤0 表示每次都拉 */
        private long catalogCacheTtlMs = 300_000L;
        /**
         * true：未进入查询面的已授权表仍出现在树中（runnable=false）；
         * false（默认）：树里只留可跑表。
         */
        private boolean showUnrunnableInTree = false;
        /**
         * Trino file AC {@code rules.json} 绝对/相对路径；主体 bind / 手动 sync 时把 impersonation 段下发到此文件。
         * 空=不下发（仅 GET impersonation-rule 导出）；与 Trino 同机或共享卷时填挂载路径。
         */
        private String impersonationRulesPath = "";
    }

    @Getter
    @Setter
    public static class Ds {
        private String url;
        private String vaultPath = LhVaultPaths.DS;
        private String user;
        private String password;
        /** 可选预置 session；本环境勿用（带 token 头会 401），优先 user/password 登录 */
        private String token;
        /** DS 项目编码（process-definition 路径）；门户专用项目 lakehouse-gov */
        private String projectCode = "184736894224512";
        /** 启动实例用 worker 组，默认 default */
        private String workerGroup = "default";
        /** 启动/创建工作流用租户；本环境 admin 绑定 root（勿用 default，admin 选 default 会报「未指定租户」） */
        private String tenantCode = "root";
    }

    @Getter
    @Setter
    public static class Flink {
        private String url;
        private String vaultPath = LhVaultPaths.FLINK;
        private String user;
        private String password;
        /** 可选 SQL Gateway 基址；空则 Worker 脚本用 flink.url/v1/sessions */
        private String sqlGatewayUrl;
        /**
         * Worker 内 Flink 发行包路径（须含 bin/sql-client.sh）。
         * DS FLINK SQL 任务会执行裸 {@code sql-client.sh}，依赖 PATH 或 FLINK_HOME。
         */
        private String home = "/opt/flink";
        /**
         * DS Flink SQL {@code SET rest.address}（Worker→JM）。
         * 空则从 {@link #url} 解析 host；跨机部署时填 JM 可达地址，如 {@code 10.0.0.181}。
         */
        private String restAddress;
        /** 对应 {@code SET rest.port}，默认 8081 */
        private Integer restPort = 8081;
    }

    @Getter
    @Setter
    public static class Spark {
        /**
         * 提交 master，注入任务 env {@code LH_SPARK_MASTER}。
         * 例：{@code local[*]} / {@code spark://lh-spark-master:7077} / {@code yarn}。
         * <p>Worker 无 YARN client 时勿填 yarn，否则 SHELL 脚本会优先用该值去连 YARN。</p>
         */
        private String master = "local[*]";
        /** Worker 内 Spark 发行包；注入 {@code SPARK_HOME}；空则脚本默认 /opt/spark */
        private String home = "/opt/spark";
        /**
         * spark-sql {@code --jars}；注入 {@code LH_SPARK_JARS}。
         * 默认 MySQL 驱动（与 deploy/ds-worker-spark-home.sh 落盘路径一致）。
         */
        private String jars = "/opt/spark/jars/mysql-connector-j-8.0.33.jar";
    }

    @Getter
    @Setter
    public static class Datax {
        /** Worker 容器内 lh-datax 路径；注入 LH_DATAX_HOME */
        private String home = "/opt/lh-datax";
    }

    @Getter
    @Setter
    public static class Clickhouse {
        private String url;
        private String vaultPath = LhVaultPaths.CLICKHOUSE;
        private String user;
        private String password;
        /** 可选；非空时合规 ALTER DELETE 追加 ON CLUSTER，校验走 clusterAllReplicas */
        private String cluster;
    }

    @Getter
    @Setter
    public static class Sqlrest {
        /** Manager UI / Admin API 根，如 http://host:18090（对齐部署台账） */
        private String managerUrl = "http://127.0.0.1:18090";
        private String username = "admin";
        private String password = "123456";
        /** 默认授权分组 / 模块（SQLREST） */
        private Long defaultGroupId = 1L;
        private Long defaultModuleId = 1L;
        /**
         * SQLREST 数据源 ID；正式环境 MUST 指向 Trino JDBC。
         * 联调环境若尚未登记 Trino，可临时指向现有 DS。
         */
        private Long datasourceId = 1L;
        /**
         * 边缘模式：仅支持 {@code gateway}（SQLREST Gateway）。
         * {@code apisix}/{@code both} 已废弃，运行时强制回落 gateway（数据服务不做 APISIX）。
         */
        private String edgeMode = "gateway";
        /** SQLREST Gateway 公网根，如 http://host:18091；门户展示与联调 */
        private String gatewayUrl = "http://127.0.0.1:18091";
        /**
         * 历史字段：曾作 APISIX upstream；APISIX 不做后仅作展示兜底，可与 {@link #gatewayUrl} 主机端口一致。
         */
        private String executorUpstream = "127.0.0.1:18091";
    }

    @Getter
    @Setter
    public static class Dataapi {
        /**
         * 发布是否强制已审批的 api_publish 单号。
         * false=联调可直接 publish；true=须携带已通过的 API-xxx。
         */
        private boolean requirePublishTicket = false;
        /** Gateway 联调探针超时毫秒 */
        private int gatewayProbeTimeoutMs = 8000;
    }

    @Getter
    @Setter
    public static class Apisix {
        private String adminUrl;
        private String vaultPath = LhVaultPaths.APISIX;
        /** bootstrap Admin Key */
        private String apiKey;
        /** 是否挂 key-auth（无 consumer 时一期可关，仅限流） */
        private boolean keyAuthEnabled = false;
    }

    @Getter
    @Setter
    public static class Superset {
        private String url;
        private String vaultPath = LhVaultPaths.SUPERSET;
        private String user;
        private String password;
    }

    @Getter
    @Setter
    public static class Minio {
        private String url;
        private String vaultPath = LhVaultPaths.MINIO;
        private String accessKey;
        private String secretKey;
        /** 平台自有对象桶（即席抽样等）；默认 lh-portal */
        private String bucket = "lh-portal";
        /** 即席数据集抽样对象前缀；完整 key = {prefix}/{ws}/{dsCode}/sample.json */
        private String datasetPrefix = "adhoc/dataset";
        /**
         * 合规证据包桶（建议独立 Object Lock 桶）；默认 lh-audit。
         * 新建时优先带 objectLock；已有无锁桶则落盘后 WORM soft-fail。
         */
        private String evidenceBucket = "lh-audit";
        /** 证据包前缀；完整 key = {prefix}/{reqNo}/{sha256}.zip */
        private String evidencePrefix = "compliance/evidence";
        /** WORM 保留天数（COMPLIANCE/GOVERNANCE）；默认 2555≈7 年 */
        private Integer evidenceWormDays = 2555;
        /** COMPLIANCE | GOVERNANCE | NONE */
        private String evidenceWormMode = "COMPLIANCE";
        /** AWS SDK / MinIO region；MinIO 常用 us-east-1 */
        private String region = "us-east-1";
    }

    @Getter
    @Setter
    public static class Openmetadata {
        private String url = "http://127.0.0.1:8585";
        private String vaultPath = LhVaultPaths.OPENMETADATA;
        /** Bot / PAT；空则启动不种子，需运维写入 Vault */
        private String token;
        private String email;
        /** 登录兜底口令（token 缺失时自动 login 取 JWT 并回写 Vault） */
        private String password;
        /** 湖仓在 OM 中的 Database Service 名（结构投影挂载点） */
        private String lakeService = "lakehouse";
        private String lakeDatabase = "default";
        /**
         * 视为「黄金」的 OM 标签 FQN（逗号分隔）；默认含 Tier.Gold。
         * 对账策略：OM 有金标 → 门户 is_gold=1，否则 0（om_wins）。
         */
        private String goldTagFqns = "Tier.Gold";
        /**
         * 标准字段/码值 soft-fail 写入的 OM Glossary 名（不双写全量枚举）。
         * 空则跳过 Glossary 同步。
         */
        private String glossaryName = "LakehouseStandard";
        /** false=不写 OM Glossary（门户 CRUD 仍可用） */
        private boolean glossarySync = true;
    }

    @Getter
    @Setter
    public static class Marquez {
        /** 经 lh-ui-auth 的对外 API，默认 :15000 */
        private String url = "http://127.0.0.1:15000";
        private String vaultPath = LhVaultPaths.MARQUEZ;
        private String user = "admin";
        private String password;
    }

    @Getter
    @Setter
    public static class Gravitino {
        private String url = "http://127.0.0.1:8090";
        private String vaultPath = LhVaultPaths.GRAVITINO;
        private String user;
        private String password;
        private String metalake = "lakehouse";
        private String catalog = "iceberg";
    }

    /** Grav→OM Schema Sync 配置 */
    @Getter
    @Setter
    public static class SchemaSync {
        /** 默认同步的 schema 列表，逗号分隔；空则拉 catalog 下全部 */
        private String schemas = "";
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Etl {
        private String defaultEngine = "flink";
        /**
         * 试跑在 DS 降级或未配置时，是否用门户 TrinoClient 本地执行可 SQL 化节点。
         * 默认 true，便于无 DS Worker 时也能验证最小链路。
         */
        private boolean localTrialFallback = true;
    }

    @Getter
    @Setter
    public static class Compute {
        /** 每工作空间一个本地 Git 工作区，脚本正文只写这里 */
        private String gitRoot = "./data/lh-git";
        /** 远程 Gitea（环境与发布 SoT）；空 token 则仅本地仓库 */
        private Gitea gitea = new Gitea();
        /**
         * 发布门禁：质量/血缘评估异常时是否按 fail 阻断（默认 true）。
         * false 时退回 skip（演示环境可关）。
         */
        private boolean publishGateHardFail = true;
        /** 开发脚本是否禁止写生产层（ods_/dwd_/dws_/ads_ 无环境前缀） */
        private boolean forbidProdLayerWrite = true;
        /** 发布须等 Gitea PR（review→prod）合并；Gitea 未启用时门禁 skip */
        private boolean requireMrMerge = true;
        /** 发布须有已审批 script_publish（SCR-）单 */
        private boolean requireScriptPublishTicket = true;
        /** 物理独立的 dev_ / stg_ Catalog 与桶 */
        private EnvIsolation envIsolation = new EnvIsolation();
    }

    @Getter
    @Setter
    public static class Gitea {
        /** 是否启用远程推送；需 baseUrl+token */
        private boolean enabled = true;
        /** 公网可达主机，例 http://127.0.0.1:3000（勿用 10.x，外网客户端不可达） */
        private String baseUrl = "http://127.0.0.1:3000";
        private String username = "lakehouse";
        private String token = "";
        private String org = "lakehouse";
        /** 空=每空间独立仓 ws-{code}；非空则强制共用该仓（运维逃生，勿默认填） */
        private String defaultRepo = "";
        /** 创建空间时是否经 API 确保远程仓库存在 */
        private boolean autoCreateRepo = true;
        /** PR 目标分支（生产 SoT） */
        private String prodBranch = "prod";
    }

    /**
     * §22 环境隔离：物理独立 Catalog 名与 MinIO 桶；开发态只写 env 前缀。
     */
    @Getter
    @Setter
    public static class EnvIsolation {
        private boolean enabled = true;
        /** 启动/首次发布时 soft 确保 Grav Catalog + MinIO 桶 */
        private boolean autoEnsure = true;
        private String devCatalog = "dev_iceberg";
        private String stgCatalog = "stg_iceberg";
        /** 空则回退 lh.gravitino.catalog */
        private String prodCatalog = "";
        private String devBucket = "lh-dev-warehouse";
        private String stgBucket = "lh-stg-warehouse";
        private String prodBucket = "warehouse";
    }

    /**
     * 生命周期 Iceberg Procedure → DS 接线
     * @see doc/生命周期.md
     */
    @Getter
    @Setter
    public static class Lifecycle {
        /**
         * Spark 中 Iceberg catalog 名（CALL {catalog}.system.*）。
         * 空则回退 {@code lh.gravitino.catalog}，再回退 {@code iceberg}。
         */
        private String sparkCatalog = "";
        /** 流程名前缀（sanitize 后写入 DS） */
        private String workflowPrefix = "lh_lc";
        /** 单表动作超时（分钟） */
        private int timeoutMinutes = 60;
        /** 日作业超时（分钟） */
        private int dailyTimeoutMinutes = 180;
        /** L1 表日作业是否跳过 rewrite（假定 Flink auto-compaction） */
        private boolean skipL1RewriteInDaily = true;
        /** DS 不可达时是否仍登记 run（degraded），默认 true */
        private boolean allowDegraded = true;
        /** 日作业 DS 流程名（默认空间不加后缀） */
        private String dailyWorkflowName = "job.iceberg.lifecycle";
        /**
         * 合规 Iceberg 硬删独立 DAG（delete → rewrite → 定向 expire）。
         * 顺序与日作业不同，禁止复用 {@link #dailyWorkflowName}。
         */
        private String complianceDeleteWorkflowName = "job.compliance.delete.iceberg";
        /** 存储画像 DS 流程名 */
        private String profileWorkflowName = "job.storage.profile_daily";
        /** 作业身份标签。Trino 仍走服务账号的 JOB 身份，禁止代执行门户用户。 */
        private String jobPrincipal = "job.lifecycle";
        /** 为 true 时每天跑画像日批；默认关，手动走 POST /lh/lifecycle/storage/collect/rerun */
        private boolean profileDailyEnabled = false;
        private int profileTableTimeoutMs = 60_000;
        private int profileMaxTables = 200;
        /**
         * 事件驱动采集（Iceberg commit 钩子）：仅 L3 低频表；默认开。
         * L1/L2 高频 CDC 禁止走本通道，避免采集风暴。
         */
        private boolean eventCollectEnabled = true;
        /** 同表事件采集冷却（分钟）；未到冷却则跳过画像 */
        private int eventCollectCooldownMinutes = 30;
        /**
         * VictoriaMetrics 根地址（例 http://vm:8428）。空=跳过写 VM，仅回写 gov_lc_table_stat。
         * 写入路径：{@code {url}/api/v1/import/prometheus}，不引 Pushgateway。
         */
        private String vmImportUrl = "";
        /** VM import 超时（毫秒） */
        private int vmImportTimeoutMs = 15_000;
        /**
         * days-to-full 默认容量（字节）。0=跳过派生写回。
         * 优先读 VM 桶 capacity / {@link #bucketCapacityBytes}；均无则用本软上限。
         * 默认 20 TiB，对齐演示桶容量量级。
         */
        private long forecastDefaultCapacityBytes = 20L * 1024 * 1024 * 1024 * 1024;
        /** 回收把握系数，对齐存储趋势 §4.4 */
        private double forecastReclaimConfidence = 0.7;
        /**
         * 桶 capacity 覆盖（字节）。MinIO 无 quota 指标时由运维填；
         * Categraf 亦可投影为 {@code lh_bucket_storage_capacity_bytes}。
         */
        private java.util.Map<String, Long> bucketCapacityBytes = new java.util.LinkedHashMap<>();
        /**
         * 分层 → 桶名（days-to-full 取该桶 capacity）。空则用内置 ODS→iceberg-ods 等默认。
         */
        private java.util.Map<String, String> layerBucketMap = new java.util.LinkedHashMap<>();
        /**
         * DS 推送回调：门户可达基址（例 http://gov:82）。空=不挂 notify 尾节点，仍接受已登录调用。
         * 完整路径为 {@code {base}/lh/lifecycle/runs/callback}。
         */
        private String callbackBaseUrl = "";
        /**
         * DS/Worker 回调共享 token（头 {@code X-Lh-Lc-Callback-Token}）。
         * 与 {@link #callbackBaseUrl} 同时非空时，投影尾节点 SHELL 自动 curl 回写。
         */
        private String callbackToken = "";
    }

    /**
     * 资产目录行为
     */
    @Getter
    @Setter
    public static class Catalog {
        /**
         * true=允许 objectName 不在 ig_ds_table（手工对象名）；
         * false=注册时必须先同步表清单。
         */
        private boolean allowManualObjectName = false;
        /** 是否启用元数据漂移日批（{@code GovAssetMetaDriftScheduler}） */
        private boolean metaDriftEnabled = false;
        /** 漂移日批 cron；默认每天 04:15 */
        private String metaDriftCron = "0 15 4 * * ?";
    }

    /**
     * AI 平台能力（LiteLLM 网关 + Milvus 向量；未启用则启发式 / 关键词）
     * @see doc/AI模型管理.md · doc/知识库.md
     */
    @Getter
    @Setter
    public static class Ai {
        /** 是否启用真实 LLM 调用 */
        private boolean enabled = false;
        /** LiteLLM Proxy 根，如 http://litellm:4000 */
        private String litellmUrl = "";
        /** LiteLLM master key（可选） */
        private String litellmMasterKey = "";
        /** 默认 chat 模型别名（空则走路由表） */
        private String defaultChatModel = "";
        /** 默认 embedding 模型别名 */
        private String defaultEmbedModel = "";
        /**
         * 向量库唯一选型：<b>Milvus</b>。未启用或不可达时知识检索降级为 MySQL 关键词。
         */
        private boolean milvusEnabled = false;
        /** 例：http://milvus:19530 */
        private String milvusUri = "";
        private String milvusToken = "";
        private String milvusDatabase = "default";
        private String milvusCollection = "lh_kb_chunk";
        /** Embedding 维度：openai text-embedding-3-small=1536；bge-m3=1024 */
        private int embedDim = 1536;
        /** 混合检索：向量权重 0~1，其余为关键词 */
        private double vectorWeight = 0.7;
        /**
         * 定时连通巡检（对启用中的模型调 LiteLLM；Vault 缺 Key / 临近过期标 warn）。
         * 现网未配 litellm-url 时巡检会跳过连通探测，仅做 Key 过期预警。
         */
        private boolean patrolEnabled = false;
        /** Spring cron；默认每小时整点 */
        private String patrolCron = "0 0 * * * ?";
        /** Key 将在 N 天内过期 → status=warn */
        private int patrolWarnDays = 14;
    }

    /**
     * 数据质量 H1：DS 质量节点回调门户 + 运行写 VM（夜莺规则包）。
     */
    @Getter
    @Setter
    public static class Quality {
        /**
         * 门户基址（DS Worker curl evaluate / runs）。例 http://dev3:82。
         * 空则 SHELL 仅按嵌入的 block 标志裁决，仍写 runs 由试跑/发布侧完成。
         */
        private String govBaseUrl = "";
        /** 写 VictoriaMetrics（复用 lifecycle.vm-import-url）；false=跳过 */
        private boolean vmWriteEnabled = true;
    }

    /**
     * 指标中心 M2：query Redis 短缓存 + 日波动采样。
     * @see doc/指标中心-引擎执行.md Phase M2
     */
    @Getter
    @Setter
    public static class Metric {
        /** query 结果 Redis 短缓存；试跑不写 */
        private boolean queryCacheEnabled = true;
        /** TTL 秒；文档建议 30～120，默认 60 */
        private int queryCacheTtlSeconds = 60;
        /** 为 true 时每天跑波动采样；默认关，避免无 Trino 环境空跑 */
        private boolean sampleDailyEnabled = false;
        /** Spring cron；默认每天 04:15 */
        private String sampleDailyCron = "0 15 4 * * ?";
        /** 单次最多采样指标数 */
        private int sampleMaxMetrics = 50;
        /** 日波动绝对值 ≥ 该百分比标 anomaly（默认 20） */
        private double anomalyThresholdPct = 20d;
        /** 物化 / 对账作业 SA 名（写 DS 参数） */
        private String jobPrincipal = "job.metric";
        /** 物化 DS 任务超时（分钟） */
        private int materializeTimeoutMinutes = 60;
        /** DS 不可达时仍登记 job_ref（soft-fail） */
        private boolean allowDegradedMaterialize = true;
        /** 热路径 / 看板检查 recon_partition 失败的回看小时数 */
        private int reconLookbackHours = 48;
    }

    /**
     * 合规删除：主体 ID HMAC 密钥 + 删除专用 SA {@code sa_compliance}。
     * <p>运行时优先读 Vault；yml 明文仅 bootstrap 种子。</p>
     */
    @Getter
    @Setter
    public static class Compliance {
        /** HMAC key 存放路径（ig_secret_store） */
        private String hmacVaultPath = LhVaultPaths.COMPLIANCE_SUBJECT_HMAC;
        /**
         * bootstrap only：首次写入 Vault 的 HMAC 密钥材料。
         * 生产导入后应清空本字段，仅保留 vault-path。
         */
        private String subjectHmacKey = "";
        /** 删除专用 SA Vault 路径 */
        private String saVaultPath = LhVaultPaths.COMPLIANCE_SA;
        /** bootstrap：sa_compliance 用户名；空则复用 ClickHouse bootstrap 用户 */
        private String saUser = "";
        /** bootstrap：sa_compliance 口令；空则复用 ClickHouse bootstrap 口令 */
        private String saPassword = "";
        /** Gravitino 主体名（审批发放写权限后登记）；身份名固定 sa_compliance */
        private String saGravitinoUser = "sa_compliance";
        /** crypto-shredding KEK Vault 路径 */
        private String cryptoKekVaultPath = LhVaultPaths.COMPLIANCE_CRYPTO_KEK;
        /** bootstrap：KEK 材料（生产导入后应清空） */
        private String cryptoKekMaterial = "lh-compliance-dev-crypto-kek";
        /** 外部 DSR intake webhook 签名密钥 Vault 路径 */
        private String intakeWebhookVaultPath = LhVaultPaths.COMPLIANCE_INTAKE_WEBHOOK;
        /** bootstrap：webhook HMAC secret（生产导入后应清空） */
        private String intakeWebhookSecret = "lh-compliance-dev-intake-secret";
        /** intake 时间戳允许偏移（秒）；0=不校验时间戳 */
        private long intakeSkewSeconds = 300L;
    }

    /**
     * §24.3 FinOps 单价：showback 金额化与 observability/costs 同源。
     */
    @Getter
    @Setter
    public static class Finops {
        /** 存储 ¥/TB·月 */
        private java.math.BigDecimal storagePerTbMonth = new java.math.BigDecimal("100");
        /** 扫描 ¥/GB */
        private java.math.BigDecimal computePerGbScan = new java.math.BigDecimal("0.50");
        private String currency = "CNY";
    }

    /**
     * 出湖与回流：到期停作业 / 出库审计（波次 J1 · A6/A7）
     */
    @Getter
    @Setter
    public static class Export {
        /** 到期扫描间隔（毫秒）；默认 5 分钟 */
        private long expireMs = 300_000L;
        /** 为 false 时关闭定时到期停作业（仍可手动 POST /lh/export/expire-due） */
        private boolean expireEnabled = true;
        /** 审批/到期时尝试写 Grav 表属性 lh.export.*；失败不阻断 */
        private boolean gravAuditEnabled = true;
    }
}
