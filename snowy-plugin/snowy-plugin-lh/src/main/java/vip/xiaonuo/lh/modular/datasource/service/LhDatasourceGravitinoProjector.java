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
package vip.xiaonuo.lh.modular.datasource.service;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.support.LhIcebergNamespaceNames;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源登记 → Gravitino Catalog 投影（无需审批）
 * <p>仅对可映射 provider 的类型写入；失败不回滚门户登记，结果写入 remark 前缀便于排查。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Service
public class LhDatasourceGravitinoProjector {

    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    /**
     * 将数据源投影为 Grav Catalog
     *
     * @return 投影结果摘要；不支持的类型返回 skipped
     */
    public Map<String, Object> project(LhDatasource ds) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<ProviderSpec> spec = resolveProvider(ds.getType());
        if (spec.isEmpty()) {
            out.put("skipped", true);
            out.put("reason", "type_not_supported:" + ds.getType());
            out.put("message", skipMessage(ds.getType()));
            out.put("gravSupported", false);
            return out;
        }
        ProviderSpec p = spec.get();
        String metalake = lhProperties.getGravitino().getMetalake();
        String catalogName = catalogNameOf(ds);
        Map<String, String> props = buildProperties(ds, p);
        try {
            Map<String, Object> r = gravitinoClient.upsertCatalog(
                    metalake, catalogName, p.catalogType, p.provider,
                    StrUtil.blankToDefault(ds.getRemark(), ds.getName()), props);
            out.putAll(r);
            out.put("skipped", false);
            out.put("gravSupported", true);
            out.put("provider", p.provider);
            if ("iceberg".equalsIgnoreCase(StrUtil.blankToDefault(ds.getType(), ""))) {
                out.putAll(ensureIcebergNamespaces(ds, List.of()));
            }
            log.info("Datasource {} projected to Grav catalog {}.{}", ds.getId(), metalake, catalogName);
        } catch (Exception e) {
            log.warn("Project datasource {} to Gravitino failed: {}", ds.getId(), e.getMessage());
            out.put("skipped", false);
            out.put("gravSupported", true);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** 门户可展示：该类型是否具备 Grav Catalog 投影能力 */
    public boolean supportsGravitino(String typeCode) {
        return resolveProvider(typeCode).isPresent();
    }

    private static String skipMessage(String type) {
        String t = StrUtil.blankToDefault(type, "").toLowerCase();
        return switch (t) {
            case "rabbitmq" ->
                    "Gravitino Messaging Catalog 目前仅支持 Kafka，不支持 RabbitMQ；"
                            + "门户登记与 Queue 清单同步（rabbitmq_mgmt）仍可用，无需投影 Grav。";
            case "redis" ->
                    "Gravitino 无 Redis Catalog；门户登记与 Key 前缀清单同步仍可用。";
            case "elasticsearch", "es" ->
                    "Gravitino 无 Elasticsearch Catalog；门户登记与 Index 清单同步仍可用。";
            case "trino" ->
                    "Gravitino 无 Trino Catalog（Trino 是查询引擎）；勿投影。";
            case "mongodb" ->
                    "Gravitino 当前无 MongoDB Catalog；门户登记仍可用。";
            default ->
                    "该类型无对应 Gravitino Catalog provider，已跳过投影（非故障）。";
        };
    }

    /** Grav Catalog 名。Iceberg 固定用湖 catalog（默认 iceberg），不能落成 ds_*。 */
    public String catalogNameOf(LhDatasource ds) {
        if (ds != null && "iceberg".equalsIgnoreCase(StrUtil.blankToDefault(ds.getType(), ""))) {
            return StrUtil.blankToDefault(lhProperties.getGravitino().getCatalog(), "iceberg");
        }
        return sanitizeCatalogName(ds == null ? null : ds.getDsCode());
    }

    /**
     * 按数据源命名空间清单在 Grav 湖 catalog 上 ensureSchema（幂等；失败写入 schemaErrors，不抛）。
     */
    public Map<String, Object> ensureIcebergNamespaces(LhDatasource ds, Collection<String> extraObjectNames) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ds == null || !"iceberg".equalsIgnoreCase(StrUtil.blankToDefault(ds.getType(), ""))) {
            out.put("skipped", true);
            return out;
        }
        String metalake = lhProperties.getGravitino().getMetalake();
        String catalog = catalogNameOf(ds);
        List<String> names = LhIcebergNamespaceNames.resolve(ds, extraObjectNames);
        List<String> ensured = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String ns : names) {
            try {
                gravitinoClient.ensureSchema(metalake, catalog, ns,
                        "created by lakehouse datasource namespace");
                ensured.add(ns);
            } catch (Exception e) {
                log.warn("Ensure Iceberg schema {}.{} failed: {}", catalog, ns, e.getMessage());
                errors.add(ns + ": " + e.getMessage());
            }
        }
        out.put("schemas", names);
        out.put("ensured", ensured);
        out.put("schemaErrors", errors);
        return out;
    }

    private Map<String, String> buildProperties(LhDatasource ds, ProviderSpec p) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        Map<String, String> props = new LinkedHashMap<>();
        if ("relational".equalsIgnoreCase(p.catalogType) && p.provider.startsWith("jdbc-")) {
            String jdbcUrl = str(secret.get("jdbcUrl"));
            if (StrUtil.isBlank(jdbcUrl)) {
                jdbcUrl = buildJdbcUrlFallback(ds, secret);
            }
            String database = resolveJdbcDatabase(ds, secret, jdbcUrl);
            // Grav MySQL：URL 通常到 host:port；PG：URL 与 jdbc-database 都须带库名
            if ("jdbc-mysql".equals(p.provider) || "jdbc-doris".equals(p.provider)) {
                props.put("jdbc-url", stripMysqlDb(jdbcUrl));
            } else {
                props.put("jdbc-url", ensurePgUrlHasDatabase(jdbcUrl, database));
            }
            props.put("jdbc-driver", p.driver);
            props.put("jdbc-user", first(secret, "username", "user"));
            props.put("jdbc-password", first(secret, "password"));
            if ("jdbc-postgresql".equals(p.provider)) {
                if (StrUtil.isBlank(database)) {
                    throw new IllegalArgumentException(
                            "PostgreSQL 投影 Grav 需要数据库名（jdbc-database），请在数据源填写 database");
                }
                props.put("jdbc-database", database);
            }
        } else if ("hive".equals(p.provider)) {
            props.put("metastore.uris", resolveHiveMetastoreUris(ds, secret));
        } else if ("lakehouse-iceberg".equals(p.provider)) {
            putIcebergProps(ds, secret, props);
        } else if ("hadoop".equals(p.provider)) {
            if ("hdfs".equals(ds.getType())) {
                putHadoopHdfsProps(ds, secret, props);
            } else {
                putHadoopS3Props(ds, secret, props);
            }
        } else if ("kafka".equals(p.provider)) {
            String bootstrap = first(secret, "bootstrap", "bootstrapServers", "host");
            String port = first(secret, "port");
            if (StrUtil.isNotBlank(port) && !bootstrap.contains(":")) {
                bootstrap = bootstrap + ":" + port;
            }
            if (StrUtil.isBlank(bootstrap) && StrUtil.isNotBlank(ds.getEndpointHost())) {
                bootstrap = ds.getEndpointHost();
                if (StrUtil.isNotBlank(ds.getEndpointPort()) && !bootstrap.contains(":")) {
                    bootstrap = bootstrap + ":" + ds.getEndpointPort();
                }
            }
            bootstrap = normalizeKafkaBootstrap(bootstrap);
            props.put("bootstrap.servers", bootstrap);
        }
        props.put("lh.dsId", ds.getId());
        props.put("lh.dsCode", ds.getDsCode());
        return props;
    }

    /**
     * Grav 跑在 lakehouse-net 内：bootstrap 用 PLAINTEXT 监听 lh-kafka:9092。
     * 门户侧 AdminClient 另走公网 EXTERNAL（见 LhKafkaInventoryDiscoverer）。
     */
    private static String normalizeKafkaBootstrap(String bootstrap) {
        String s = StrUtil.trim(bootstrap);
        if (StrUtil.isBlank(s)) {
            return "lh-kafka:9092";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("lh-kafka") || lower.startsWith("lh-kafka:")
                || lower.startsWith("10.0.0.34:")
                || lower.contains("127.0.0.1")
                || lower.startsWith("127.0.0.1:")) {
            return "lh-kafka:9092";
        }
        return s;
    }

    /**
     * Hive Catalog 需要 HMS thrift URI（默认 9083），不是 HiveServer2 JDBC（10000）。
     * 门户常填 HS2；若未显式给 metastoreUris，则把 10000 改写为 9083，并把 *.127.0.0.1 换成内网 IP，
     * 避免 Docker 内公网 DNS 导致连错地址。
     */
    private static String resolveHiveMetastoreUris(LhDatasource ds, Map<String, Object> secret) {
        String uris = first(secret, "metastoreUris", "metastore.uris", "metastore");
        if (StrUtil.isNotBlank(uris)) {
            if (!uris.contains("://")) {
                uris = "thrift://" + uris;
            }
            return rewriteDatagooHostToLanIp(uris);
        }
        String host = first(secret, "host", ds.getEndpointHost());
        String port = first(secret, "metastorePort", "hive.metastore.port");
        if (StrUtil.isBlank(port)) {
            String hs2Port = first(secret, "port", ds.getEndpointPort());
            // HS2 默认 10000 → 推断 HMS 9083；已显式填 metastorePort 则尊重
            port = ("10000".equals(hs2Port) || StrUtil.isBlank(hs2Port)) ? "9083" : hs2Port;
        }
        if (StrUtil.isBlank(host)) {
            throw new IllegalArgumentException("Hive 投影 Grav 需要 metastore 主机（host / metastore.uris）");
        }
        host = rewriteDatagooHostToLanIp(host);
        return "thrift://" + host + ":" + port;
    }

    /**
     * Docker 内公网 DNS 常把 *.127.0.0.1 解析到公网 IP；内网服务须走台账 IP。
     */
    private static String rewriteDatagooHostToLanIp(String hostOrUri) {
        if (StrUtil.isBlank(hostOrUri)) {
            return hostOrUri;
        }
        String s = hostOrUri;
        s = s.replace("127.0.0.1", "10.0.0.34");
        s = s.replace("127.0.0.1", "182.44.68.9");
        return s;
    }

    /** MinIO / S3 → Grav FILESET(hadoop) */
    private void putHadoopS3Props(LhDatasource ds, Map<String, Object> secret, Map<String, String> props) {
        String endpoint = first(secret, "endpoint", "host", ds.getEndpointHost());
        String port = first(secret, "port", ds.getEndpointPort());
        String ak = first(secret, "accessKey", "access-key", "accessKeyId", "username");
        String sk = first(secret, "secretKey", "secret-key", "secretAccessKey", "password");
        if (StrUtil.isBlank(ak) || StrUtil.isBlank(sk) || sk.startsWith("REPLACE_") || "******".equals(sk)) {
            Map<String, String> platform = credentialResolver.minio();
            if (StrUtil.isBlank(ak)) {
                ak = StrUtil.blankToDefault(platform.get("accessKey"), "");
            }
            if (StrUtil.isBlank(sk) || sk.startsWith("REPLACE_") || "******".equals(sk)) {
                sk = StrUtil.blankToDefault(platform.get("secretKey"), "");
            }
        }
        String bucket = first(secret, "bucket", "path", "database");
        if (StrUtil.isBlank(bucket)) {
            bucket = "warehouse";
        }
        if (StrUtil.isBlank(endpoint)) {
            throw new IllegalArgumentException("S3/MinIO 投影需要 endpoint");
        }
        if (StrUtil.isBlank(ak) || StrUtil.isBlank(sk) || sk.startsWith("REPLACE_")) {
            throw new IllegalArgumentException("S3/MinIO 投影需要有效 accessKey/secretKey（请补全 Vault 或 lh.minio.*）");
        }
        String epUrl = endpoint;
        if (!epUrl.startsWith("http")) {
            epUrl = "http://" + epUrl;
        }
        if (StrUtil.isNotBlank(port) && !epUrl.matches(".*:\\d+(/.*)?")) {
            // http://host → http://host:9009
            int scheme = epUrl.indexOf("://");
            String rest = scheme >= 0 ? epUrl.substring(scheme + 3) : epUrl;
            if (!rest.contains(":")) {
                epUrl = epUrl + ":" + port;
            }
        }
        String location = bucket.startsWith("s3a://") ? bucket : ("s3a://" + bucket.replaceFirst("^/+", ""));
        props.put("location", location);
        props.put("filesystem-providers", "s3");
        props.put("default-filesystem-provider", "s3");
        props.put("s3-endpoint", epUrl);
        props.put("s3-access-key-id", ak);
        props.put("s3-secret-access-key", sk);
        // MinIO 通常要 path-style
        props.put("gravitino.bypass.fs.s3a.path.style.access", "true");
        props.put("gravitino.bypass.fs.s3a.connection.ssl.enabled",
                epUrl.startsWith("https") ? "true" : "false");
    }

    /** HDFS → Grav FILESET(hadoop) 本地/HCFS */
    private static void putHadoopHdfsProps(LhDatasource ds, Map<String, Object> secret, Map<String, String> props) {
        String host = first(secret, "nameNode", "host", "endpoint", ds.getEndpointHost());
        String port = first(secret, "port", ds.getEndpointPort());
        String path = first(secret, "path", "database");
        if (StrUtil.isBlank(path)) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String location;
        if (host.startsWith("hdfs://")) {
            location = host;
        } else {
            location = "hdfs://" + host + (StrUtil.isNotBlank(port) ? ":" + port : "") + path;
        }
        props.put("location", location);
    }

    /** 解析 JDBC 库名：Vault → 实体 → URL path */
    private static String resolveJdbcDatabase(LhDatasource ds, Map<String, Object> secret, String jdbcUrl) {
        String db = first(secret, "database");
        if (StrUtil.isBlank(db) && ds != null) {
            db = StrUtil.nullToEmpty(ds.getDatabaseName());
        }
        if (StrUtil.isBlank(db)) {
            db = parseDatabaseFromJdbcUrl(jdbcUrl);
        }
        return StrUtil.trim(db);
    }

    private static String parseDatabaseFromJdbcUrl(String jdbcUrl) {
        if (StrUtil.isBlank(jdbcUrl)) {
            return "";
        }
        try {
            // jdbc:postgresql://host:5432/dbname?x 或 jdbc:mysql://host:3306/db
            int scheme = jdbcUrl.indexOf("://");
            if (scheme < 0) {
                return "";
            }
            int pathStart = jdbcUrl.indexOf('/', scheme + 3);
            if (pathStart < 0 || pathStart + 1 >= jdbcUrl.length()) {
                return "";
            }
            String path = jdbcUrl.substring(pathStart + 1);
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            int slash = path.indexOf('/');
            if (slash >= 0) {
                path = path.substring(0, slash);
            }
            return path.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** PG URL 若缺库名则补上，与 jdbc-database 一致 */
    private static String ensurePgUrlHasDatabase(String jdbcUrl, String database) {
        if (StrUtil.isBlank(jdbcUrl) || StrUtil.isBlank(database)) {
            return jdbcUrl;
        }
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
            return jdbcUrl;
        }
        String parsed = parseDatabaseFromJdbcUrl(jdbcUrl);
        if (StrUtil.isNotBlank(parsed)) {
            return jdbcUrl;
        }
        int scheme = jdbcUrl.indexOf("://") + 3;
        int q = jdbcUrl.indexOf('?', scheme);
        String base = q > 0 ? jdbcUrl.substring(0, q) : jdbcUrl;
        String query = q > 0 ? jdbcUrl.substring(q) : "";
        if (base.endsWith("/")) {
            return base + database + query;
        }
        return base + "/" + database + query;
    }

    private String buildJdbcUrlFallback(LhDatasource ds, Map<String, Object> secret) {
        String host = first(secret, "host", ds.getEndpointHost());
        String port = first(secret, "port", ds.getEndpointPort());
        String db = first(secret, "database", ds.getDatabaseName());
        return switch (ds.getType()) {
            case "mysql", "doris" -> "jdbc:mysql://" + host + ":" + port + "/" + db;
            case "postgresql", "pg" -> "jdbc:postgresql://" + host + ":" + port + "/" + db;
            case "clickhouse" -> "jdbc:clickhouse://" + host + ":" + port + "/" + db;
            default -> "";
        };
    }

    private static String stripMysqlDb(String jdbcUrl) {
        if (StrUtil.isBlank(jdbcUrl)) {
            return jdbcUrl;
        }
        // jdbc:mysql://host:3306/db?x → jdbc:mysql://host:3306
        if (jdbcUrl.startsWith("jdbc:mysql://") || jdbcUrl.startsWith("jdbc:mariadb://")) {
            int scheme = jdbcUrl.indexOf("://") + 3;
            int slash = jdbcUrl.indexOf('/', scheme);
            if (slash > 0) {
                int q = jdbcUrl.indexOf('?', slash);
                return jdbcUrl.substring(0, slash) + (q > 0 ? jdbcUrl.substring(q) : "");
            }
        }
        return jdbcUrl;
    }

    /**
     * Gravitino lakehouse-iceberg。catalog 名由 {@link #catalogNameOf} 固定为湖 catalog。
     * Hive Metastore 用 thrift URI；warehouse 必须是存储路径，不能写成 Trino 的 REST warehouse。
     */
    private void putIcebergProps(LhDatasource ds, Map<String, Object> secret, Map<String, String> props) {
        String warehouse = first(secret, "warehouse", "warehouseUri");
        if (StrUtil.isBlank(warehouse)) {
            throw new IllegalArgumentException("Iceberg 投影需要 warehouse（如 s3a://warehouse/）");
        }
        String catalogType = first(secret, "catalogType", "catalog-backend").toLowerCase(Locale.ROOT);
        boolean jdbc = catalogType.contains("jdbc");
        props.put("catalog-backend", jdbc ? "jdbc" : "hive");
        props.put("warehouse", warehouse);
        String uri = first(secret, "uri", "metastoreUri", "metastore.uris");
        if (StrUtil.isBlank(uri)) {
            String host = first(secret, "host", "endpointHost");
            if (StrUtil.isBlank(host)) {
                host = StrUtil.blankToDefault(ds.getEndpointHost(), "");
            }
            String port = first(secret, "port");
            if (StrUtil.isBlank(port)) {
                port = StrUtil.blankToDefault(ds.getEndpointPort(), jdbc ? "3306" : "9083");
            }
            if (StrUtil.isBlank(host)) {
                throw new IllegalArgumentException("Iceberg 投影需要 Catalog 地址（Hive Metastore 或 JDBC）");
            }
            uri = jdbc ? ("jdbc:mysql://" + host + ":" + port) : ("thrift://" + host + ":" + port);
        }
        props.put("uri", uri);
        String endpoint = first(secret, "s3.endpoint", "s3Endpoint", "endpoint");
        if (StrUtil.isNotBlank(endpoint) && (endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            props.put("io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
            props.put("s3.endpoint", endpoint);
            props.put("s3.path-style-access", "true");
            String access = first(secret, "s3.access-key-id", "accessKey", "access-key");
            String secretKey = first(secret, "s3.secret-access-key", "secretKey", "secret-key");
            if (StrUtil.isNotBlank(access)) {
                props.put("s3.access-key-id", access);
            }
            if (StrUtil.isNotBlank(secretKey)) {
                props.put("s3.secret-access-key", secretKey);
            }
        }
        if (jdbc) {
            String user = first(secret, "jdbc.user", "username", "user");
            String password = first(secret, "jdbc.password", "password");
            if (StrUtil.isNotBlank(user)) {
                props.put("jdbc.user", user);
            }
            if (StrUtil.isNotBlank(password)) {
                props.put("jdbc.password", password);
            }
        }
    }

    private Optional<ProviderSpec> resolveProvider(String type) {
        if (StrUtil.isBlank(type)) {
            return Optional.empty();
        }
        return switch (type) {
            case "mysql" -> Optional.of(new ProviderSpec("relational", "jdbc-mysql",
                    "com.mysql.cj.jdbc.Driver"));
            case "postgresql", "pg" -> Optional.of(new ProviderSpec("relational", "jdbc-postgresql",
                    "org.postgresql.Driver"));
            case "doris" -> Optional.of(new ProviderSpec("relational", "jdbc-doris",
                    "com.mysql.cj.jdbc.Driver"));
            case "clickhouse" -> Optional.of(new ProviderSpec("relational", "jdbc-clickhouse",
                    "com.clickhouse.jdbc.ClickHouseDriver"));
            case "hive" -> Optional.of(new ProviderSpec("relational", "hive", ""));
            case "iceberg" -> Optional.of(new ProviderSpec("relational", "lakehouse-iceberg", ""));
            case "s3", "minio" -> Optional.of(new ProviderSpec("fileset", "hadoop", ""));
            case "hdfs" -> Optional.of(new ProviderSpec("fileset", "hadoop", ""));
            case "kafka" -> Optional.of(new ProviderSpec("messaging", "kafka", ""));
            // Grav Messaging 仅 kafka；无 Redis / RabbitMQ / ES / Trino / Mongo Catalog
            default -> Optional.empty();
        };
    }

    private static String sanitizeCatalogName(String dsCode) {
        String s = StrUtil.blankToDefault(dsCode, "ds_unknown");
        s = s.replaceAll("[^a-zA-Z0-9_]", "_");
        if (!Character.isLetter(s.charAt(0))) {
            s = "c_" + s;
        }
        return StrUtil.sub(s, 0, 64);
    }

    private static String first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    private static String first(Map<String, Object> m, String key, String fallback) {
        String v = first(m, key);
        return StrUtil.isNotBlank(v) ? v : StrUtil.nullToEmpty(fallback);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private record ProviderSpec(String catalogType, String provider, String driver) {
    }
}
