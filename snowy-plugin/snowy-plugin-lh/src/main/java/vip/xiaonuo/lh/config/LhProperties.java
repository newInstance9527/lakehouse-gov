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
    private Compute compute = new Compute();

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
        /** 平台自有对象桶（即席抽样 / 证据包等）；默认 lh-portal */
        private String bucket = "lh-portal";
        /** 即席数据集抽样对象前缀；完整 key = {prefix}/{ws}/{dsCode}/sample.json */
        private String datasetPrefix = "adhoc/dataset";
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
        /** 存储画像 DS 流程名 */
        private String profileWorkflowName = "job.storage.profile_daily";
        /** 作业身份标签。Trino 仍走服务账号的 JOB 身份，禁止代执行门户用户。 */
        private String jobPrincipal = "job.lifecycle";
        /** 为 true 时每天跑画像日批；默认关，手动走 POST /lh/lifecycle/storage/collect/rerun */
        private boolean profileDailyEnabled = false;
        private int profileTableTimeoutMs = 60_000;
        private int profileMaxTables = 200;
        /**
         * VictoriaMetrics 根地址（例 http://vm:8428）。空=跳过写 VM，仅回写 gov_lc_table_stat。
         * 写入路径：{@code {url}/api/v1/import/prometheus}，不引 Pushgateway。
         */
        private String vmImportUrl = "";
        /** VM import 超时（毫秒） */
        private int vmImportTimeoutMs = 15_000;
        /**
         * days-to-full 默认容量（字节）。0=跳过派生写回。
         * 正式应由桶 capacity（Categraf）承接；现网未配时可用软上限做闭环。
         * 默认 20 TiB，对齐演示桶容量量级。
         */
        private long forecastDefaultCapacityBytes = 20L * 1024 * 1024 * 1024 * 1024;
        /** 回收把握系数，对齐存储趋势 §4.4 */
        private double forecastReclaimConfidence = 0.7;
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
    }
}
