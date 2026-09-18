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
    private Clickhouse clickhouse = new Clickhouse();
    private Sqlrest sqlrest = new Sqlrest();
    private Apisix apisix = new Apisix();
    private Superset superset = new Superset();
    private Minio minio = new Minio();
    private Openmetadata openmetadata = new Openmetadata();
    private Gravitino gravitino = new Gravitino();
    private SchemaSync schemaSync = new SchemaSync();
    private Etl etl = new Etl();

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
        /** DS Token（优先于 user/password 调用 API） */
        private String token;
    }

    @Getter
    @Setter
    public static class Flink {
        private String url;
        private String vaultPath = LhVaultPaths.FLINK;
        private String user;
        private String password;
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
        private String managerUrl;
    }

    @Getter
    @Setter
    public static class Apisix {
        private String adminUrl;
        private String vaultPath = LhVaultPaths.APISIX;
        /** bootstrap Admin Key */
        private String apiKey;
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
        /** 湖仓在 OM 中的 Database Service 名（结构投影挂载点） */
        private String lakeService = "lakehouse";
        private String lakeDatabase = "default";
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
    }
}
