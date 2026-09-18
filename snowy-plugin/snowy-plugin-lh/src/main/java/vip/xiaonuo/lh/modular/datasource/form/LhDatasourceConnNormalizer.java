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
package vip.xiaonuo.lh.modular.datasource.form;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourceAddParam;

import java.util.*;

/**
 * 连接参数归一化（对齐前端 RegisterSourceModal.submit）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class LhDatasourceConnNormalizer {

    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "secretKey", "token", "privateKey", "authHeader", "accessKey"
    );

    /**
     * 取完整 conn（调用方应先经 ViewAssembler.enrichParamFromFe）
     */
    public Map<String, Object> mergeConn(LhDatasourceAddParam param) {
        Map<String, Object> conn = new LinkedHashMap<>();
        if (param.getConn() != null) {
            conn.putAll(param.getConn());
        }
        putIfAbsent(conn, "host", param.getHost());
        putIfAbsent(conn, "port", param.getPort());
        putIfAbsent(conn, "database", param.getDatabase());
        putIfAbsent(conn, "user", param.getUser());
        putIfAbsent(conn, "password", param.getPassword());
        putIfAbsent(conn, "jdbcUrl", param.getJdbcUrl());
        putIfAbsent(conn, "bootstrap", first(param.getBootstrap(), param.getBootstrapServers()));
        putIfAbsent(conn, "bootstrapServers", first(param.getBootstrapServers(), param.getBootstrap()));
        putIfAbsent(conn, "topics", param.getTopics());
        putIfAbsent(conn, "queues", param.getQueues());
        putIfAbsent(conn, "bucket", param.getBucket());
        putIfAbsent(conn, "endpoint", param.getEndpoint());
        putIfAbsent(conn, "accessKey", param.getAccessKey());
        putIfAbsent(conn, "secretKey", param.getSecretKey());
        putIfAbsent(conn, "path", param.getPath());
        putIfAbsent(conn, "baseURL", param.getBaseURL());
        putIfAbsent(conn, "httpUrl", param.getBaseURL());
        putIfAbsent(conn, "token", param.getToken());
        putIfAbsent(conn, "extra", param.getExtra());
        putIfAbsent(conn, "schema", param.getSchema());
        putIfAbsent(conn, "access", param.getAccess());
        putIfAbsent(conn, "sid", param.getSid());
        putIfAbsent(conn, "namespace", param.getNamespace());
        putIfAbsent(conn, "vhost", param.getVhost());
        putIfAbsent(conn, "tenant", param.getTenant());
        putIfAbsent(conn, "db", param.getDb());
        putIfAbsent(conn, "warehouse", param.getWarehouse());
        putIfAbsent(conn, "nameNode", param.getNameNode());
        putIfAbsent(conn, "serviceUrl", param.getServiceUrl());
        putIfAbsent(conn, "zkQuorum", param.getZkQuorum());
        putIfAbsent(conn, "feNodes", param.getFeNodes());
        putIfAbsent(conn, "pollCycle", param.getPollCycle());
        return conn;
    }

    public NormalizedConn normalize(String typeLabel, Map<String, Object> conn, String defaultPort) {
        Map<String, Object> c = conn == null ? Map.of() : conn;
        String host = first(c, "host", "bootstrap", "bootstrapServers", "endpoint",
                "nameNode", "serviceUrl", "zkQuorum", "baseURL", "httpUrl");
        if (StrUtil.isNotBlank(host)) {
            host = host.replaceFirst("^https?://", "");
            int slash = host.indexOf('/');
            if (slash > 0) {
                host = host.substring(0, slash);
            }
        }
        String port = first(c, "port");
        if (StrUtil.isBlank(port)) {
            port = defaultPort;
        }
        String database = first(c, "database", "sid", "namespace", "vhost", "tenant", "db", "path", "warehouse");
        String user = first(c, "user", "accessKey", "username");
        String password = first(c, "password", "secretKey", "token");
        String extra = first(c, "extra", "feNodes", "warehouse");
        String schema = first(c, "schema", "topics", "queues");
        String access = first(c, "access", "pollCycle");

        NormalizedConn n = new NormalizedConn();
        n.host = host;
        n.port = port;
        n.database = database;
        n.user = user;
        n.password = password;
        n.extra = extra;
        n.schemaSummary = schema;
        n.accessMode = access;
        n.rawConn = new LinkedHashMap<>(c);
        return n;
    }

    public Map<String, Object> masked(Map<String, Object> conn) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (conn == null) {
            return m;
        }
        conn.forEach((k, v) -> {
            if (SECRET_KEYS.contains(k)) {
                if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                    m.put(k, "******");
                }
            } else {
                m.put(k, v);
            }
        });
        return m;
    }

    public Map<String, Object> secretPayload(Map<String, Object> conn, NormalizedConn n) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (conn != null) {
            m.putAll(conn);
        }
        m.put("username", n.user);
        m.put("password", n.password);
        if (StrUtil.isNotBlank(n.host)) {
            m.put("host", n.host);
        }
        if (StrUtil.isNotBlank(n.port)) {
            m.put("port", n.port);
        }
        if (StrUtil.isNotBlank(n.database)) {
            m.put("database", n.database);
        }
        return m;
    }

    public String mapPurposes(String purpose, String purposesJson) {
        if (StrUtil.isNotBlank(purposesJson) && purposesJson.trim().startsWith("[")) {
            return purposesJson;
        }
        if (StrUtil.isBlank(purpose)) {
            return "[\"ingest\"]";
        }
        return switch (purpose.trim()) {
            case "仅元数据采集(OM)", "meta_collect" -> "[\"meta_collect\"]";
            case "出湖目标", "export" -> "[\"export\"]";
            case "数据入湖", "ingest" -> "[\"ingest\"]";
            case "query_gateway" -> "[\"query_gateway\"]";
            default -> {
                if (purpose.contains("元数据")) {
                    yield "[\"meta_collect\"]";
                }
                if (purpose.contains("出湖")) {
                    yield "[\"export\"]";
                }
                yield JSONUtil.toJsonStr(List.of(purpose));
            }
        };
    }

    private static String first(String a, String b) {
        return StrUtil.isNotBlank(a) ? a : b;
    }

    private static void putIfAbsent(Map<String, Object> m, String k, Object v) {
        if (v == null) {
            return;
        }
        if (v instanceof String s && StrUtil.isBlank(s)) {
            return;
        }
        m.putIfAbsent(k, v);
    }

    private static String first(Map<String, Object> c, String... keys) {
        for (String k : keys) {
            Object v = c.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    public static class NormalizedConn {
        public String host;
        public String port;
        public String database;
        public String user;
        public String password;
        public String extra;
        public String schemaSummary;
        public String accessMode;
        public Map<String, Object> rawConn;
    }
}
