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
package vip.xiaonuo.lh.modular.datasource.discover;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis 清单发现：登记 Key 前缀优先；否则 SCAN 限流列举（禁止 KEYS *）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Component
@Order(10)
public class LhRedisInventoryDiscoverer implements LhInventoryDiscoverer {

    private static final int SCAN_MAX = 500;
    private static final int SCAN_COUNT = 50;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int SOCKET_TIMEOUT_MS = 3000;

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public boolean supports(String typeCode) {
        return "redis".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.KEY_PREFIX;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String host = first(secret, "host", "endpoint");
        String port = first(secret, "port");
        if (StrUtil.isBlank(host) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            host = ds.getEndpointHost();
        }
        if (StrUtil.isBlank(port) && StrUtil.isNotBlank(ds.getEndpointPort())) {
            port = ds.getEndpointPort();
        }
        if (StrUtil.isBlank(host)) {
            throw new CommonException("Redis 清单同步需要 host（Vault / 端点）；请检查连接或维护 Key 前缀清单后重试");
        }
        if (StrUtil.isBlank(port)) {
            port = "6379";
        }
        int portNum;
        try {
            portNum = Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            throw new CommonException("Redis 端口无效: {}；请检查 Vault port", port);
        }

        int db = 0;
        String dbStr = first(secret, "db", "database");
        if (StrUtil.isBlank(dbStr) && StrUtil.isNotBlank(ds.getDatabaseName())
                && StrUtil.isNumeric(ds.getDatabaseName().trim())) {
            dbStr = ds.getDatabaseName().trim();
        }
        if (StrUtil.isNotBlank(dbStr)) {
            try {
                db = Integer.parseInt(dbStr.trim());
            } catch (NumberFormatException ignored) {
                db = 0;
            }
        }

        List<String> registered = resolveRegisteredPrefixes(ds, secret);
        String password = first(secret, "password");
        String user = first(secret, "user", "username");

        DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(CONNECT_TIMEOUT_MS)
                .socketTimeoutMillis(SOCKET_TIMEOUT_MS)
                .database(db);
        if (StrUtil.isNotBlank(password)) {
            if (StrUtil.isNotBlank(user)) {
                cfg.user(user).password(password);
            } else {
                cfg.password(password);
            }
        }
        JedisClientConfig clientConfig = cfg.build();

        try (Jedis jedis = new Jedis(new HostAndPort(host.trim(), portNum), clientConfig)) {
            jedis.ping();
            if (!registered.isEmpty()) {
                List<LhRemoteInventoryItem> items = new ArrayList<>();
                for (String prefix : registered) {
                    LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                    m.name = prefix;
                    m.engine = "redis";
                    m.encoding = "UTF-8";
                    String match = toScanPattern(prefix);
                    try {
                        long sample = sampleMatchCount(jedis, match, 20);
                        m.comment = "key_prefix; SCAN sample≈" + sample;
                        m.rowCount = sample;
                    } catch (Exception e) {
                        m.comment = "key_prefix; SCAN 校验跳过: " + StrUtil.maxLength(e.getMessage(), 80);
                    }
                    items.add(m);
                }
                return LhInventoryDiscoverResult.remote("redis_prefix", LhInventoryObjectKinds.KEY_PREFIX, items);
            }

            // 无登记前缀：SCAN 限流列举 key 样本（禁止 KEYS *）
            List<String> keys = scanKeys(jedis, "*", SCAN_MAX);
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            for (String key : keys) {
                LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                m.name = key;
                m.engine = "redis";
                m.encoding = "UTF-8";
                m.comment = "key sample via SCAN (max=" + SCAN_MAX + ")";
                items.add(m);
            }
            LhInventoryDiscoverResult r = LhInventoryDiscoverResult.remote(
                    "redis_scan", LhInventoryObjectKinds.KEY, items);
            if (items.size() >= SCAN_MAX) {
                r.hint = "Redis SCAN 已达上限 " + SCAN_MAX + "，仅为样本；建议登记 Key 前缀清单后同步";
            } else if (items.isEmpty()) {
                r.hint = "Redis SCAN 未发现 Key（db=" + db + "）；可维护 schemaSummary 前缀清单后重试";
            }
            return r;
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException(
                    "Redis 清单同步失败（禁止 KEYS *；请检查 host/port/password/db 或登记 Key 前缀）: {}",
                    e.getMessage());
        }
    }

    private static List<String> resolveRegisteredPrefixes(LhDatasource ds, Map<String, Object> secret) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.addAll(splitList(ds.getSchemaSummary()));
        out.addAll(splitList(first(secret, "schema", "keyPrefixes", "prefixes", "keys")));
        // conn_masked 可能残留非密前缀字段
        if (StrUtil.isNotBlank(ds.getConnMasked()) && JSONUtil.isTypeJSON(ds.getConnMasked())) {
            try {
                Map<String, Object> masked = JSONUtil.parseObj(ds.getConnMasked());
                out.addAll(splitList(first(masked, "schema", "keyPrefixes", "prefixes", "keys")));
            } catch (Exception ignored) {
            }
        }
        return out.stream().filter(StrUtil::isNotBlank).collect(Collectors.toList());
    }

    private static String toScanPattern(String prefix) {
        String p = StrUtil.trim(prefix);
        if (p.contains("*") || p.contains("?")) {
            return p;
        }
        return p.endsWith(":") ? p + "*" : p + "*";
    }

    private static long sampleMatchCount(Jedis jedis, String pattern, int limit) {
        ScanParams params = new ScanParams().match(pattern).count(Math.min(SCAN_COUNT, limit));
        String scanMark = ScanParams.SCAN_POINTER_START;
        long n = 0;
        int rounds = 0;
        do {
            ScanResult<String> res = jedis.scan(scanMark, params);
            n += res.getResult() == null ? 0 : res.getResult().size();
            scanMark = res.getCursor();
            if (n >= limit) {
                return n;
            }
            rounds++;
        } while (!"0".equals(scanMark) && rounds < 20);
        return n;
    }

    private static List<String> scanKeys(Jedis jedis, String pattern, int max) {
        ScanParams params = new ScanParams().match(pattern).count(SCAN_COUNT);
        String scanMark = ScanParams.SCAN_POINTER_START;
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        int rounds = 0;
        do {
            ScanResult<String> res = jedis.scan(scanMark, params);
            if (res.getResult() != null) {
                for (String k : res.getResult()) {
                    if (StrUtil.isNotBlank(k)) {
                        keys.add(k);
                        if (keys.size() >= max) {
                            return new ArrayList<>(keys);
                        }
                    }
                }
            }
            scanMark = res.getCursor();
            rounds++;
        } while (!"0".equals(scanMark) && rounds < 200);
        return new ArrayList<>(keys);
    }

    private static List<String> splitList(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split("[,;/\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    private static String first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }
}
