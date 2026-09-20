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
package vip.xiaonuo.lh.modular.catalog.preview;

import cn.hutool.core.util.StrUtil;
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
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis：SCAN 样例键值（禁止 KEYS *）。
 */
@Slf4j
@Component
@Order(30)
public class RedisPreviewAdapter implements GovAssetPreviewAdapter {

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean supports(GovAssetPreviewContext ctx) {
        return "redis".equalsIgnoreCase(PreviewAdapterSupport.dsType(ctx.getPrimaryDs()))
                && StrUtil.isNotBlank(ctx.getObjectName());
    }

    @Override
    public Map<String, Object> preview(GovAssetPreviewContext ctx) {
        LhDatasource ds = ctx.getPrimaryDs();
        String pattern = ctx.getObjectName().trim();
        if (!pattern.contains("*")) {
            pattern = pattern.endsWith(":") ? pattern + "*" : pattern + "*";
        }
        int limit = ctx.getLimit();
        Map<String, Object> r = ctx.newResult();
        r.put("qualifiedName", pattern);
        try {
            Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
            String host = PreviewAdapterSupport.firstNonBlank(
                    PreviewAdapterSupport.str(secret.get("host")),
                    PreviewAdapterSupport.str(secret.get("endpoint")),
                    ds.getEndpointHost());
            String port = PreviewAdapterSupport.firstNonBlank(
                    PreviewAdapterSupport.str(secret.get("port")), ds.getEndpointPort(), "6379");
            String password = PreviewAdapterSupport.firstNonBlank(
                    PreviewAdapterSupport.str(secret.get("password")), "");
            if (StrUtil.isBlank(host)) {
                return PreviewAdapterSupport.emptyFail(r, "redis", "Redis 无 host，无法预览");
            }
            int portNum = Integer.parseInt(port.trim());
            JedisClientConfig conf = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(3000)
                    .socketTimeoutMillis(3000)
                    .password(StrUtil.isBlank(password) ? null : password)
                    .build();
            List<String> columns = List.of("key", "type", "value");
            List<Map<String, Object>> rows = new ArrayList<>();
            try (Jedis jedis = new Jedis(new HostAndPort(host, portNum), conf)) {
                String cursor = ScanParams.SCAN_POINTER_START;
                ScanParams params = new ScanParams().match(pattern).count(50);
                int guard = 0;
                do {
                    ScanResult<String> sr = jedis.scan(cursor, params);
                    cursor = sr.getCursor();
                    for (String key : sr.getResult()) {
                        String type = jedis.type(key);
                        String value = sampleValue(jedis, key, type);
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("key", key);
                        row.put("type", type);
                        row.put("value", value);
                        rows.add(row);
                        if (rows.size() >= limit) {
                            break;
                        }
                    }
                } while (!"0".equals(cursor) && rows.size() < limit && ++guard < 20);
            }
            r.put("ok", true);
            r.put("source", "redis");
            r.put("columns", columns);
            r.put("rows", rows);
            r.put("rowCount", rows.size());
            r.put("message", "Redis SCAN 样例（探查，非分析路径）");
            return r;
        } catch (Exception e) {
            log.warn("Redis preview fail pattern={}: {}", pattern, e.getMessage());
            Map<String, Object> fail = PreviewAdapterSupport.emptyFail(r, "redis",
                    "Redis 预览失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            fail.put("degraded", true);
            return fail;
        }
    }

    private static String sampleValue(Jedis jedis, String key, String type) {
        try {
            String t = StrUtil.blankToDefault(type, "none");
            String v;
            switch (t) {
                case "string" -> v = jedis.get(key);
                case "hash" -> v = String.valueOf(jedis.hgetAll(key));
                case "list" -> v = String.valueOf(jedis.lrange(key, 0, 4));
                case "set" -> v = String.valueOf(jedis.srandmember(key, 5));
                case "zset" -> v = String.valueOf(jedis.zrange(key, 0, 4));
                default -> v = "(" + t + ")";
            }
            if (v != null && v.length() > 500) {
                return v.substring(0, 500) + "…";
            }
            return v;
        } catch (Exception e) {
            return "(" + e.getMessage() + ")";
        }
    }
}
