package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 门户数据源 → JDBC URL（Vault / SQLREST / 测连共用）。
 */
public final class LhJdbcUrlBuilder {

    private LhJdbcUrlBuilder() {
    }

    /**
     * @param type     门户 typeCode（小写）
     * @param host     主机
     * @param port     端口
     * @param database 库名 / 默认库
     * @param extra    额外查询串
     * @param conn     原始 conn（可读 jdbcUrl / ssl 等）
     */
    public static String build(String type, String host, String port, String database,
                               String extra, Map<String, Object> conn) {
        String t = StrUtil.blankToDefault(type, "").toLowerCase(Locale.ROOT);
        if (conn != null) {
            Object jdbc = conn.get("jdbcUrl");
            if (jdbc != null && StrUtil.isNotBlank(String.valueOf(jdbc))) {
                String existing = String.valueOf(jdbc).trim();
                // 旧版曾写入 jdbc:es://http://…，SQLREST ProductType 只认 jdbc:esdriver://
                if (isTrustedJdbcUrl(t, existing)) {
                    return existing;
                }
            }
        }
        String h = StrUtil.blankToDefault(host, "").trim();
        String p = StrUtil.blankToDefault(port, "").trim();
        String db = StrUtil.blankToDefault(database, "").trim();
        String ex = StrUtil.blankToDefault(extra, "").trim();

        return switch (t) {
            case "mysql", "mariadb" -> "jdbc:mysql://" + h + ":" + p + "/" + db
                    + "?useSSL=false&allowPublicKeyRetrieval=true"
                    + (StrUtil.isNotBlank(ex) ? "&" + ex : "");
            case "pg", "postgresql" -> "jdbc:postgresql://" + h + ":" + p + "/" + db
                    + (StrUtil.isNotBlank(ex) ? "?" + ex : "");
            case "oracle" -> buildOracle(h, p, db);
            case "sqlserver" -> "jdbc:sqlserver://" + h + ":" + p + ";databaseName=" + db
                    + (StrUtil.isNotBlank(ex) ? ";" + ex.replace("&", ";") : "");
            case "clickhouse" -> "jdbc:clickhouse://" + h + ":" + p + "/"
                    + StrUtil.blankToDefault(db, "default")
                    + (StrUtil.isNotBlank(ex) ? "?" + ex : "");
            case "doris", "starrocks" -> "jdbc:mysql://" + h + ":" + p + "/" + db + "?useSSL=false";
            case "trino" -> buildTrino(h, p, db, conn);
            case "oceanbase" -> "jdbc:oceanbase://" + h + ":" + p + "/" + db
                    + (StrUtil.isNotBlank(ex) ? "?" + ex : "");
            case "hive" -> buildHive(h, p, db, ex, conn);
            case "mongodb" -> buildMongo(h, p, db, ex, conn);
            case "elasticsearch", "es" -> buildElasticsearch(h, p);
            case "http_api", "tableau", "superset", "airflow" -> buildHttpRestful(conn, h, p);
            default -> "";
        };
    }

    /** 从 Vault secret / 端点字段拼装（投影缺 jdbcUrl 时回退） */
    public static String buildFromSecret(String type, Map<String, Object> secret,
                                         String endpointHost, String endpointPort, String databaseName) {
        Map<String, Object> conn = secret != null ? new LinkedHashMap<>(secret) : new LinkedHashMap<>();
        String host = firstNonBlank(conn.get("host"), endpointHost);
        String port = firstNonBlank(conn.get("port"), endpointPort);
        String db = firstNonBlank(conn.get("database"), conn.get("db"), databaseName);
        String extra = firstNonBlank(conn.get("extra"));
        return build(type, host, port, db, extra, conn);
    }

    /**
     * HiveServer2：NONE/LDAP 用用户名密码；Kerberos 追加 principal=
     * 例 jdbc:hive2://hs2:10000/default;principal=hive/_HOST@REALM
     */
    private static String buildHive(String host, String port, String database, String extra,
                                    Map<String, Object> conn) {
        String db = StrUtil.blankToDefault(database, "default");
        StringBuilder url = new StringBuilder("jdbc:hive2://")
                .append(host).append(":")
                .append(StrUtil.blankToDefault(port, "10000"))
                .append("/").append(db);
        List<String> parts = new ArrayList<>();
        String auth = firstNonBlank(conn != null ? conn.get("auth") : null);
        String principal = firstNonBlank(conn != null ? conn.get("principal") : null);
        if (StrUtil.isNotBlank(auth) && "Kerberos".equalsIgnoreCase(auth.trim())
                && StrUtil.isNotBlank(principal)) {
            parts.add("principal=" + principal.trim());
        } else if (StrUtil.isNotBlank(auth) && !"NONE".equalsIgnoreCase(auth.trim())
                && !"Kerberos".equalsIgnoreCase(auth.trim())) {
            // LDAP 等：auth=ldap（部分发行版识别）
            parts.add("auth=" + auth.trim());
        }
        if (StrUtil.isNotBlank(extra)) {
            parts.add(extra.replace("&", ";").replaceFirst("^;+", ""));
        }
        if (!parts.isEmpty()) {
            url.append(";").append(String.join(";", parts));
        }
        return url.toString();
    }

    private static String buildMongo(String host, String port, String database, String extra,
                                     Map<String, Object> conn) {
        String db = StrUtil.blankToDefault(database, "admin");
        StringBuilder url = new StringBuilder("jdbc:mongodb://")
                .append(host).append(":").append(StrUtil.blankToDefault(port, "27017"))
                .append("/").append(db);
        List<String> q = new ArrayList<>();
        String authSource = firstNonBlank(conn != null ? conn.get("authSource") : null);
        if (StrUtil.isNotBlank(authSource)) {
            q.add("authSource=" + authSource.trim());
        }
        String replicaSet = firstNonBlank(conn != null ? conn.get("replicaSet") : null);
        if (StrUtil.isNotBlank(replicaSet)) {
            q.add("replicaSet=" + replicaSet.trim());
        }
        if (StrUtil.isNotBlank(extra)) {
            q.add(extra.startsWith("?") || extra.startsWith("&") ? extra.replaceFirst("^[?&]+", "") : extra);
        }
        if (!q.isEmpty()) {
            url.append("?").append(String.join("&", q));
        }
        return url.toString();
    }

    /** jdbc:trino://host:port/catalog/schema */
    private static String buildTrino(String host, String port, String database, Map<String, Object> conn) {
        String catalog = firstNonBlank(conn != null ? conn.get("catalog") : null, "hive");
        String schema = StrUtil.blankToDefault(database, "default");
        return "jdbc:trino://" + host + ":" + StrUtil.blankToDefault(port, "8080")
                + "/" + catalog + "/" + schema;
    }

    private static String buildOracle(String host, String port, String db) {
        if (StrUtil.isNotBlank(db) && (db.contains(".") || db.contains("/")
                || db.toLowerCase(Locale.ROOT).contains("service"))) {
            String svc = db.replace("service:", "").replace("SERVICE:", "");
            return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + svc;
        }
        return "jdbc:oracle:thin:@" + host + ":" + port + ":" + db;
    }

    /**
     * SQLREST {@code com.gitee.esdriver.driver.EsDriver}：
     * {@code jdbc:esdriver://host:port}（见 ProductTypeEnum.ELASTICSEARCH）。
     * 认证走 SQLREST body 的 username/password，不写进 URL。
     */
    private static String buildElasticsearch(String host, String port) {
        if (StrUtil.isBlank(host)) {
            return "";
        }
        String h = host.replaceFirst("(?i)^https?://", "").replaceAll("/+$", "");
        String p = StrUtil.blankToDefault(port, "9200");
        if (h.contains(":") && !h.startsWith("[")) {
            int colon = h.lastIndexOf(':');
            if (colon > 0) {
                String maybePort = h.substring(colon + 1);
                if (maybePort.chars().allMatch(Character::isDigit)) {
                    if (StrUtil.isBlank(port)) {
                        p = maybePort;
                    }
                    h = h.substring(0, colon);
                }
            }
        }
        return "jdbc:esdriver://" + h + ":" + p;
    }

    /** Vault 里已有 jdbcUrl 是否可直接交给 SQLREST（避免旧错误前缀卡住重试）。 */
    private static boolean isTrustedJdbcUrl(String type, String jdbcUrl) {
        if (StrUtil.isBlank(jdbcUrl)) {
            return false;
        }
        if ("elasticsearch".equals(type) || "es".equals(type)) {
            return jdbcUrl.regionMatches(true, 0, "jdbc:esdriver://", 0, "jdbc:esdriver://".length());
        }
        return true;
    }

    /**
     * SQLREST HTTP / RestfulDriver：{@code jdbc:restful:https://host/path}
     */
    private static String buildHttpRestful(Map<String, Object> conn, String host, String port) {
        String base = firstNonBlank(
                conn != null ? conn.get("baseURL") : null,
                conn != null ? conn.get("baseUrl") : null,
                conn != null ? conn.get("endpoint") : null,
                conn != null ? conn.get("url") : null,
                host);
        if (StrUtil.isBlank(base)) {
            return "";
        }
        base = base.trim();
        if (base.regionMatches(true, 0, "jdbc:restful:", 0, "jdbc:restful:".length())) {
            return base;
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            String scheme = useHttps(conn) ? "https" : "http";
            if (StrUtil.isNotBlank(port) && !base.contains(":")) {
                base = scheme + "://" + base + ":" + port;
            } else {
                base = scheme + "://" + base;
            }
        }
        return "jdbc:restful:" + base;
    }

    private static boolean useHttps(Map<String, Object> conn) {
        if (conn == null || conn.isEmpty()) {
            return false;
        }
        Object ssl = conn.get("ssl");
        if (ssl != null) {
            String raw = String.valueOf(ssl).trim();
            String s = raw.toLowerCase(Locale.ROOT);
            if ("启用".equals(raw) || "true".equals(s) || "1".equals(s)
                    || "yes".equals(s) || "on".equals(s) || "https".equals(s)) {
                return true;
            }
            if ("禁用".equals(raw) || "false".equals(s) || "0".equals(s)
                    || "no".equals(s) || "off".equals(s) || "http".equals(s)) {
                return false;
            }
        }
        Object scheme = conn.get("scheme");
        if (scheme != null && "https".equalsIgnoreCase(String.valueOf(scheme).trim())) {
            return true;
        }
        Object useSsl = conn.get("useSsl");
        if (useSsl != null) {
            return Boolean.parseBoolean(String.valueOf(useSsl));
        }
        return false;
    }

    private static String firstNonBlank(Object... vals) {
        if (vals == null) {
            return "";
        }
        for (Object v : vals) {
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }
}
