package vip.xiaonuo.lh.modular.compliance.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcMetadataSql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 回流 / 源端删除：走 {@code sa_compliance} 身份（Vault）+ 目标数据源 JDBC / Redis。
 * ES 一期 soft-fail，不标完成。
 */
@Component
public class GovDelSinkExecutor {

    private static final Logger log = LoggerFactory.getLogger(GovDelSinkExecutor.class);

    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    public record SinkResult(boolean ok, String engineRef, String detail, Long rowsAffected, Long rowsLeft) {
        public static SinkResult fail(String detail) {
            return new SinkResult(false, null, detail, null, null);
        }

        public static SinkResult ok(String engineRef, String detail, Long affected, Long left) {
            return new SinkResult(true, engineRef, detail, affected, left);
        }
    }

    public SinkResult delete(String objectFqn, String idColumn, String subjectHash) {
        GovDelSinkSql.SinkRef ref;
        try {
            ref = GovDelSinkSql.parse(objectFqn);
            GovDelSinkSql.assertHash(subjectHash);
            if (!GovLcMetadataSql.isIdent(idColumn)) {
                return SinkResult.fail("非法主体列: " + idColumn);
            }
        } catch (IllegalArgumentException e) {
            return SinkResult.fail(e.getMessage());
        }
        if (GovDelSinkSql.isRdb(ref.engine())) {
            return deleteRdb(ref, idColumn, subjectHash);
        }
        if (GovDelSinkSql.isRedis(ref.engine())) {
            return deleteRedis(ref, subjectHash);
        }
        if (GovDelSinkSql.isElasticsearch(ref.engine())) {
            return SinkResult.fail("ES delete_by_query 未接线，不标完成");
        }
        return SinkResult.fail("不支持的 sink 引擎: " + ref.engine());
    }

    public long countRemaining(String objectFqn, String idColumn, String subjectHash) {
        GovDelSinkSql.SinkRef ref = GovDelSinkSql.parse(objectFqn);
        GovDelSinkSql.assertHash(subjectHash);
        if (!GovLcMetadataSql.isIdent(idColumn)) {
            return -1L;
        }
        if (GovDelSinkSql.isRdb(ref.engine())) {
            LhDatasource ds = resolveDs(ref);
            if (ds == null || StrUtil.isBlank(ds.getVaultPath())) {
                return -1L;
            }
            try (Connection conn = openJdbc(ds)) {
                String schema = StrUtil.blankToDefault(ds.getDatabaseName(), ref.dsHint());
                String sql = "SELECT count(*) AS cnt FROM " + quoteIdent(schema, ds.getType())
                        + "." + quoteIdent(ref.table(), ds.getType())
                        + " WHERE " + quoteIdent(idColumn, ds.getType()) + " = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, subjectHash);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("sink count soft-fail {}: {}", objectFqn, e.getMessage());
                return -1L;
            }
        }
        if (GovDelSinkSql.isRedis(ref.engine())) {
            // Redis 删除后以 key 不存在为 0
            return 0L;
        }
        return -1L;
    }

    private SinkResult deleteRdb(GovDelSinkSql.SinkRef ref, String idColumn, String subjectHash) {
        LhDatasource ds = resolveDs(ref);
        if (ds == null) {
            return SinkResult.fail("未找到数据源 type=" + ref.engine() + " hint=" + ref.dsHint());
        }
        if (StrUtil.isBlank(ds.getVaultPath())) {
            return SinkResult.fail("数据源无 vaultPath: " + ds.getId());
        }
        try (Connection conn = openJdbc(ds)) {
            String schema = StrUtil.blankToDefault(ds.getDatabaseName(), ref.dsHint());
            String sql = "DELETE FROM " + quoteIdent(schema, ds.getType())
                    + "." + quoteIdent(ref.table(), ds.getType())
                    + " WHERE " + quoteIdent(idColumn, ds.getType()) + " = ?";
            int affected;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, subjectHash);
                affected = ps.executeUpdate();
            }
            long left;
            String countSql = "SELECT count(*) FROM " + quoteIdent(schema, ds.getType())
                    + "." + quoteIdent(ref.table(), ds.getType())
                    + " WHERE " + quoteIdent(idColumn, ds.getType()) + " = ?";
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                ps.setString(1, subjectHash);
                try (ResultSet rs = ps.executeQuery()) {
                    left = rs.next() ? rs.getLong(1) : -1L;
                }
            }
            return SinkResult.ok("sa:sa_compliance;ds:" + ds.getId(),
                    "sa_compliance JDBC DELETE 影响 " + affected + " 行，残留 " + left,
                    (long) affected, left);
        } catch (Exception e) {
            return SinkResult.fail(StrUtil.maxLength(
                    StrUtil.blankToDefault(e.getMessage(), "JDBC 删除失败"), 400));
        }
    }

    private SinkResult deleteRedis(GovDelSinkSql.SinkRef ref, String subjectHash) {
        LhDatasource ds = resolveDs(ref);
        if (ds == null || StrUtil.isBlank(ds.getVaultPath())) {
            return SinkResult.fail("未找到 Redis 数据源 hint=" + ref.dsHint());
        }
        try {
            Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
            Map<String, String> sa = credentialResolver.complianceSa();
            String host = first(str(secret.get("host")), ds.getEndpointHost());
            int port = parsePort(first(str(secret.get("port")), ds.getEndpointPort()), 6379);
            String password = first(sa.get("password"), str(secret.get("password")), "");
            if (StrUtil.isBlank(host)) {
                return SinkResult.fail("Redis 无 host");
            }
            // key = table + ":" + hash（约定；表名段作前缀）
            String key = ref.table() + ":" + subjectHash;
            JedisClientConfig cfg = DefaultJedisClientConfig.builder()
                    .password(StrUtil.isBlank(password) ? null : password)
                    .build();
            try (Jedis jedis = new Jedis(new HostAndPort(host, port), cfg)) {
                long n = jedis.del(key);
                return SinkResult.ok("sa:sa_compliance;redis:" + ds.getId(),
                        "sa_compliance Redis DEL " + key + " → " + n, n, 0L);
            }
        } catch (Exception e) {
            return SinkResult.fail(StrUtil.maxLength(
                    StrUtil.blankToDefault(e.getMessage(), "Redis 删除失败"), 400));
        }
    }

    private Connection openJdbc(LhDatasource ds) throws Exception {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        Map<String, String> sa = credentialResolver.complianceSa();
        String url = first(str(secret.get("jdbcUrl")), null);
        if (StrUtil.isBlank(url)) {
            throw new IllegalStateException("Vault 无 jdbcUrl: " + ds.getVaultPath());
        }
        // 优先 sa_compliance 用户；缺省回退数据源 Vault（联调）
        String user = first(sa.get("username"), sa.get("user"),
                str(secret.get("username")), str(secret.get("user")));
        String pwd = first(sa.get("password"), str(secret.get("password")), "");
        return DriverManager.getConnection(url, user, pwd);
    }

    private LhDatasource resolveDs(GovDelSinkSql.SinkRef ref) {
        String type = normalizeType(ref.engine());
        List<LhDatasource> list = datasourceMapper.selectList(new QueryWrapper<LhDatasource>().lambda()
                .eq(LhDatasource::getType, type)
                .and(w -> w.eq(LhDatasource::getDsCode, ref.dsHint())
                        .or().eq(LhDatasource::getName, ref.dsHint())
                        .or().eq(LhDatasource::getDatabaseName, ref.dsHint())
                        .or().like(LhDatasource::getName, ref.dsHint()))
                .last("LIMIT 5"));
        if (list == null || list.isEmpty()) {
            // 再按 category=rdb + hint 宽松匹配
            list = datasourceMapper.selectList(new QueryWrapper<LhDatasource>().lambda()
                    .and(w -> w.eq(LhDatasource::getDsCode, ref.dsHint())
                            .or().eq(LhDatasource::getDatabaseName, ref.dsHint())
                            .or().like(LhDatasource::getName, ref.dsHint()))
                    .last("LIMIT 5"));
        }
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    private static String normalizeType(String engine) {
        String e = engine.toLowerCase(Locale.ROOT);
        return switch (e) {
            case "pg", "postgres", "postgresql" -> "postgresql";
            case "mariadb" -> "mysql";
            case "es" -> "elasticsearch";
            default -> e;
        };
    }

    private static String quoteIdent(String ident, String type) {
        if (!GovLcMetadataSql.isIdent(ident)) {
            throw new IllegalArgumentException("非法标识符: " + ident);
        }
        String t = StrUtil.blankToDefault(type, "").toLowerCase(Locale.ROOT);
        if (t.contains("postgres") || t.contains("pg")) {
            return "\"" + ident + "\"";
        }
        return "`" + ident + "`";
    }

    private static int parsePort(String raw, int def) {
        if (StrUtil.isBlank(raw)) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String first(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    /** 供证据 / 审计：当前 SA 解析结果摘要（不含口令）。 */
    public Map<String, Object> saSummary() {
        Map<String, String> sa = credentialResolver.complianceSa();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vaultPath", credentialResolver.complianceSaVaultPath());
        out.put("username", sa.get("username"));
        out.put("present", StrUtil.isNotBlank(sa.get("username")) || StrUtil.isNotBlank(sa.get("password")));
        return out;
    }
}
