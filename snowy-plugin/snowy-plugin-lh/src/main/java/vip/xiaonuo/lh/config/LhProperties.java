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
    private Apisix apisix = new Apisix();
    private Superset superset = new Superset();
    private Minio minio = new Minio();
    private Openmetadata openmetadata = new Openmetadata();
    private Marquez marquez = new Marquez();
    private Gravitino gravitino = new Gravitino();
    private SchemaSync schemaSync = new SchemaSync();
    private Etl etl = new Etl();
    private Catalog catalog = new Catalog();

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
        private String vaultPath = LhVaultPaths.TRINO;
        /** bootstrap only */
        private String user;
        private String password;
        private boolean insecureSsl = true;
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
        /** Manager UI / Admin API 根，如 http://host:18090 */
        private String managerUrl;
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
         * APISIX upstream 节点 host:port。
         * 生产应指向 SQLREST Executor；当前环境可临时用 Gateway（如 host:18091）。
         */
        private String executorUpstream = "127.0.0.1:18091";
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
}
