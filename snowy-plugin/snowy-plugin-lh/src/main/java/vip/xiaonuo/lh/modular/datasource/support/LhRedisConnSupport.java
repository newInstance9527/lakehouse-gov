package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisSentinelPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import vip.xiaonuo.common.exception.CommonException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Redis 连接辅助：Standalone / Sentinel / Cluster 一等支持。
 * <p>Jedis 5 中 Standalone {@link Jedis} 与 {@link JedisCluster} 无公共父类可直接作泛型，
 * 故对外暴露 {@link Session}（ping / scan）。
 */
public final class LhRedisConnSupport {

    public enum DeployMode {
        STANDALONE,
        SENTINEL,
        CLUSTER
    }

    /** 测连 / SCAN / KV 读（兼容 Standalone / Sentinel / Cluster）。 */
    public interface Session {
        String ping();

        ScanResult<String> scan(String cursor, ScanParams params);

        String get(String key);

        java.util.List<String> mget(String... keys);

        java.util.Map<String, String> hgetAll(String key);
    }

    private LhRedisConnSupport() {
    }

    public static DeployMode resolveMode(Map<String, Object> conn) {
        String mode = first(conn, "mode", "deployMode");
        if (StrUtil.isBlank(mode)) {
            if (StrUtil.isNotBlank(first(conn, "masterName", "sentinelMaster"))) {
                return DeployMode.SENTINEL;
            }
            String nodes = first(conn, "nodes", "clusterNodes", "sentinels");
            if (StrUtil.isNotBlank(nodes) && (nodes.contains(",") || nodes.contains(";"))) {
                return DeployMode.CLUSTER;
            }
            return DeployMode.STANDALONE;
        }
        String m = mode.toLowerCase(Locale.ROOT);
        if (m.contains("sentinel") || m.contains("哨兵")) {
            return DeployMode.SENTINEL;
        }
        if (m.contains("cluster") || m.contains("集群")) {
            return DeployMode.CLUSTER;
        }
        return DeployMode.STANDALONE;
    }

    public static JedisClientConfig clientConfig(Map<String, Object> conn, int database) {
        DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(3000)
                .socketTimeoutMillis(5000)
                .database(Math.max(0, database));
        String password = first(conn, "password");
        String user = first(conn, "user", "username");
        if (StrUtil.isNotBlank(password)) {
            if (StrUtil.isNotBlank(user)) {
                cfg.user(user).password(password);
            } else {
                cfg.password(password);
            }
        }
        return cfg.build();
    }

    public static int resolveDb(Map<String, Object> conn) {
        String dbStr = first(conn, "db", "database");
        if (StrUtil.isNotBlank(dbStr) && dbStr.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(dbStr.trim());
        }
        return 0;
    }

    /**
     * 对当前部署模式执行操作（测连 / SCAN）；保证关闭资源。
     */
    public static <T> T withClient(Map<String, Object> conn, Function<Session, T> action) {
        DeployMode mode = resolveMode(conn);
        int db = resolveDb(conn);
        JedisClientConfig cfg = clientConfig(conn, mode == DeployMode.CLUSTER ? 0 : db);
        return switch (mode) {
            case CLUSTER -> withCluster(conn, cfg, action);
            case SENTINEL -> withSentinel(conn, cfg, action);
            case STANDALONE -> withStandalone(conn, cfg, action);
        };
    }

    private static <T> T withStandalone(Map<String, Object> conn, JedisClientConfig cfg,
                                        Function<Session, T> action) {
        HostAndPort hap = singleHost(conn, 6379);
        try (Jedis jedis = new Jedis(hap, cfg)) {
            return action.apply(wrap(jedis));
        }
    }

    private static <T> T withCluster(Map<String, Object> conn, JedisClientConfig cfg,
                                     Function<Session, T> action) {
        Set<HostAndPort> nodes = parseHostPorts(
                firstNonBlank(first(conn, "nodes", "clusterNodes"), first(conn, "host", "endpoint")),
                6379);
        if (nodes.isEmpty()) {
            throw new CommonException("Redis Cluster 需要 nodes 或 host（host:port 逗号分隔）");
        }
        try (JedisCluster cluster = new JedisCluster(nodes, cfg)) {
            return action.apply(wrap(cluster));
        }
    }

    private static <T> T withSentinel(Map<String, Object> conn, JedisClientConfig cfg,
                                      Function<Session, T> action) {
        String master = first(conn, "masterName", "sentinelMaster", "master");
        if (StrUtil.isBlank(master)) {
            throw new CommonException("Redis Sentinel 需要 masterName（如 mymaster）");
        }
        Set<HostAndPort> sentinels = parseHostPorts(
                firstNonBlank(first(conn, "nodes", "sentinels", "sentinelNodes"),
                        first(conn, "host", "endpoint")),
                26379);
        if (sentinels.isEmpty()) {
            throw new CommonException("Redis Sentinel 需要 nodes/host（sentinel 地址，默认端口 26379）");
        }
        try (JedisSentinelPool pool = new JedisSentinelPool(master, sentinels, cfg, cfg)) {
            try (Jedis jedis = pool.getResource()) {
                return action.apply(wrap(jedis));
            }
        }
    }

    private static Session wrap(Jedis jedis) {
        return new Session() {
            @Override
            public String ping() {
                return jedis.ping();
            }

            @Override
            public ScanResult<String> scan(String cursor, ScanParams params) {
                return jedis.scan(cursor, params);
            }

            @Override
            public String get(String key) {
                return jedis.get(key);
            }

            @Override
            public List<String> mget(String... keys) {
                return jedis.mget(keys);
            }

            @Override
            public Map<String, String> hgetAll(String key) {
                return jedis.hgetAll(key);
            }
        };
    }

    private static Session wrap(JedisCluster cluster) {
        return new Session() {
            @Override
            public String ping() {
                return cluster.ping();
            }

            @Override
            public ScanResult<String> scan(String cursor, ScanParams params) {
                return cluster.scan(cursor, params);
            }

            @Override
            public String get(String key) {
                return cluster.get(key);
            }

            @Override
            public List<String> mget(String... keys) {
                return cluster.mget(keys);
            }

            @Override
            public Map<String, String> hgetAll(String key) {
                return cluster.hgetAll(key);
            }
        };
    }

    public static HostAndPort singleHost(Map<String, Object> conn, int defaultPort) {
        String host = first(conn, "host", "endpoint");
        String port = first(conn, "port");
        // Cluster/Sentinel 可只填 nodes：取首个作 Standalone 回退
        if (StrUtil.isBlank(host)) {
            Set<HostAndPort> fromNodes = parseHostPorts(first(conn, "nodes", "clusterNodes", "sentinels"), defaultPort);
            if (!fromNodes.isEmpty()) {
                return fromNodes.iterator().next();
            }
            throw new CommonException("Redis 需要 host 或 nodes");
        }
        host = host.replaceFirst("^redis(s)?://", "").trim();
        if (host.contains(",") || host.contains(";")) {
            Set<HostAndPort> set = parseHostPorts(host, defaultPort);
            if (!set.isEmpty()) {
                return set.iterator().next();
            }
        }
        if (host.contains(":") && !host.startsWith("[")) {
            int colon = host.lastIndexOf(':');
            String p = host.substring(colon + 1);
            if (StrUtil.isNumeric(p)) {
                return new HostAndPort(host.substring(0, colon), Integer.parseInt(p));
            }
        }
        int portNum = defaultPort;
        if (StrUtil.isNotBlank(port) && StrUtil.isNumeric(port.trim())) {
            portNum = Integer.parseInt(port.trim());
        }
        return new HostAndPort(host.trim(), portNum);
    }

    public static Set<HostAndPort> parseHostPorts(String raw, int defaultPort) {
        LinkedHashSet<HostAndPort> out = new LinkedHashSet<>();
        if (StrUtil.isBlank(raw)) {
            return out;
        }
        for (String part : raw.split("[,;\\s]+")) {
            String p = part.trim().replaceFirst("^redis(s)?://", "");
            if (StrUtil.isBlank(p) || p.startsWith("×") || p.contains("master+")) {
                continue;
            }
            if (p.contains(":") && !p.startsWith("[")) {
                int colon = p.lastIndexOf(':');
                String h = p.substring(0, colon).trim();
                String portStr = p.substring(colon + 1).trim();
                if (StrUtil.isBlank(h) || !StrUtil.isNumeric(portStr)) {
                    continue;
                }
                out.add(new HostAndPort(h, Integer.parseInt(portStr)));
            } else if (StrUtil.isNotBlank(p)) {
                out.add(new HostAndPort(p, defaultPort));
            }
        }
        return out;
    }

    public static List<String> splitList(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : raw.split("[,;/\\n]+")) {
            if (StrUtil.isNotBlank(p)) {
                out.add(p.trim());
            }
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        return StrUtil.isNotBlank(a) ? a : b;
    }

    private static String first(Map<String, Object> m, String... keys) {
        if (m == null) {
            return "";
        }
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }
}
