package vip.xiaonuo.lh.modular.datasource.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.engine.SqlrestClient;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhConsumerBinding;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceStatusEnum;
import vip.xiaonuo.lh.modular.datasource.mapper.LhConsumerBindingMapper;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将门户 ig_datasource 投影为 SQLREST Manager 数据源（多数据源 SQL2API）。
 * <p>凭证从 Vault 读取；绑定写入 ig_consumer_binding（consumer_type=sqlrest）。</p>
 */
@Component
public class LhDatasourceSqlrestProjector {

    public static final String CONSUMER_TYPE = "sqlrest";

    /** SQLREST 原生支持且门户可映射的类型 */
    private static final Set<String> PROJECTABLE = Set.of(
            "mysql", "mariadb", "pg", "postgresql", "oracle", "sqlserver",
            "clickhouse", "doris", "hive", "mongodb", "elasticsearch",
            "starrocks", "oceanbase");

    @Resource
    private SqlrestClient sqlrestClient;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhConsumerBindingMapper bindingMapper;

    public static boolean isProjectable(String portalType) {
        return portalType != null && PROJECTABLE.contains(portalType.toLowerCase(Locale.ROOT));
    }

    /**
     * 投影或刷新；返回 sqlrestDatasourceId 等信息
     */
    public Map<String, Object> project(LhDatasource ds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dsId", ds.getId());
        result.put("dsCode", ds.getDsCode());
        result.put("name", ds.getName());

        if (LhDatasourceStatusEnum.REVOKED.getValue().equals(ds.getStatus())
                || LhDatasourceStatusEnum.PAUSED.getValue().equals(ds.getStatus())) {
            result.put("ok", false);
            result.put("skipped", true);
            result.put("message", "数据源已停用/吊销，跳过投影");
            return result;
        }
        String type = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        if (!isProjectable(type)) {
            result.put("ok", false);
            result.put("skipped", true);
            result.put("message", "SQLREST 暂不支持该类型: " + type
                    + "（湖仓联邦请用可投影的 ADS/CK/Doris，或为 SQLREST 安装 Trino JDBC 驱动后扩展）");
            return result;
        }

        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String jdbcUrl = str(secret.get("jdbcUrl"));
        if (StrUtil.isBlank(jdbcUrl)) {
            result.put("ok", false);
            result.put("message", "Vault 缺少 jdbcUrl，无法投影");
            upsertBindingError(ds.getId(), "Vault 缺少 jdbcUrl");
            return result;
        }
        String username = firstNonBlank(secret, "username", "user", "jdbc-user");
        String password = firstNonBlank(secret, "password", "jdbc-password");
        SqlrestTypeMapping mapped = mapType(type);
        String version = pickDriverVersion(mapped.sqlrestType());
        if (StrUtil.isBlank(version)) {
            result.put("ok", false);
            result.put("message", "无法获取 SQLREST 驱动版本: " + mapped.sqlrestType());
            return result;
        }

        String dsName = "lh_" + StrUtil.blankToDefault(ds.getDsCode(), ds.getId());
        LhConsumerBinding existing = findBinding(ds.getId());
        Long sqlrestId = null;
        if (existing != null && StrUtil.isNotBlank(existing.getProjection())) {
            sqlrestId = JSONUtil.parseObj(existing.getProjection()).getLong("sqlrestDatasourceId");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", dsName);
        body.put("type", mapped.sqlrestType());
        body.put("version", version);
        body.put("driver", mapped.driverClass());
        body.put("url", jdbcUrl);
        body.put("username", username);
        body.put("password", password);
        body.put("poolConfig", defaultPool());

        Map<String, Object> srResp;
        if (sqlrestId != null) {
            body.put("id", sqlrestId);
            srResp = sqlrestClient.updateDatasource(body);
        } else {
            Long matchedId = findSqlrestIdByName(dsName);
            if (matchedId != null) {
                body.put("id", matchedId);
                sqlrestId = matchedId;
                srResp = sqlrestClient.updateDatasource(body);
            } else {
                srResp = sqlrestClient.createDatasource(body);
                sqlrestId = extractDatasourceId(srResp);
                if (sqlrestId == null) {
                    sqlrestId = findSqlrestIdByName(dsName);
                }
            }
        }

        boolean ok = Boolean.TRUE.equals(srResp.get("ok")) && sqlrestId != null;
        result.put("ok", ok);
        result.put("degraded", Boolean.TRUE.equals(srResp.get("degraded")));
        result.put("sqlrest", srResp);
        result.put("sqlrestDatasourceId", sqlrestId);
        result.put("sqlrestType", mapped.sqlrestType());
        result.put("sqlrestName", dsName);

        if (ok) {
            upsertBindingOk(ds.getId(), sqlrestId, mapped.sqlrestType(), dsName);
        } else {
            upsertBindingError(ds.getId(), String.valueOf(srResp.get("message")));
            if (StrUtil.isBlank(String.valueOf(result.get("message")))) {
                result.put("message", srResp.get("message"));
            }
        }
        return result;
    }

    public Long resolveSqlrestDatasourceId(String portalDsId) {
        LhConsumerBinding b = findBinding(portalDsId);
        if (b != null && "synced".equalsIgnoreCase(b.getSyncState()) && StrUtil.isNotBlank(b.getProjection())) {
            return JSONUtil.parseObj(b.getProjection()).getLong("sqlrestDatasourceId");
        }
        return null;
    }

    private Long findSqlrestIdByName(String name) {
        Map<String, Object> list = sqlrestClient.listDatasources(name, 1, 50);
        if (!Boolean.TRUE.equals(list.get("ok")) || !(list.get("data") instanceof List<?> rows)) {
            return null;
        }
        for (Object row : rows) {
            JSONObject o = JSONUtil.parseObj(row);
            if (name.equals(o.getStr("name"))) {
                return o.getLong("id");
            }
        }
        return null;
    }

    private String pickDriverVersion(String sqlrestType) {
        Map<String, Object> drivers = sqlrestClient.listDrivers(sqlrestType);
        if (!Boolean.TRUE.equals(drivers.get("ok")) || !(drivers.get("data") instanceof List<?> list) || list.isEmpty()) {
            return switch (sqlrestType) {
                case "MYSQL" -> "mysql-8.4";
                case "POSTGRESQL" -> "postgresql-13";
                case "CLICKHOUSE" -> null;
                default -> null;
            };
        }
        // 取最后一个（通常更新）
        JSONObject last = JSONUtil.parseObj(list.get(list.size() - 1));
        String ver = last.getStr("driverVersion");
        if (StrUtil.isNotBlank(ver)) {
            return ver;
        }
        return null;
    }

    private LhConsumerBinding findBinding(String dsId) {
        return bindingMapper.selectOne(new QueryWrapper<LhConsumerBinding>().lambda()
                .eq(LhConsumerBinding::getDsId, dsId)
                .eq(LhConsumerBinding::getConsumerType, CONSUMER_TYPE)
                .last("LIMIT 1"));
    }

    private void upsertBindingOk(String dsId, Long sqlrestId, String sqlrestType, String name) {
        LhConsumerBinding b = findBinding(dsId);
        Map<String, Object> proj = new LinkedHashMap<>();
        proj.put("sqlrestDatasourceId", sqlrestId);
        proj.put("sqlrestType", sqlrestType);
        proj.put("sqlrestName", name);
        // consumer_id 用门户 dsId 保持唯一键稳定；SQLREST id 只放 projection
        String consumerId = "ds:" + dsId;
        if (b == null) {
            b = new LhConsumerBinding();
            b.setId(IdUtil.getSnowflakeNextId());
            b.setRevision(1);
            b.setDsId(dsId);
            b.setConsumerType(CONSUMER_TYPE);
            b.setConsumerId(consumerId);
            b.setStatus("ENABLE");
            b.setDeleteFlag("NOT_DELETE");
            b.setProjection(JSONUtil.toJsonStr(proj));
            b.setSyncState("synced");
            b.setLastSyncAt(new Date());
            b.setLastError(null);
            bindingMapper.insert(b);
        } else {
            b.setConsumerId(consumerId);
            b.setProjection(JSONUtil.toJsonStr(proj));
            b.setSyncState("synced");
            b.setLastSyncAt(new Date());
            b.setLastError(null);
            b.setRevision(b.getRevision() == null ? 1 : b.getRevision() + 1);
            bindingMapper.updateById(b);
        }
    }

    private void upsertBindingError(String dsId, String err) {
        LhConsumerBinding b = findBinding(dsId);
        String consumerId = "ds:" + dsId;
        if (b == null) {
            b = new LhConsumerBinding();
            b.setId(IdUtil.getSnowflakeNextId());
            b.setRevision(1);
            b.setDsId(dsId);
            b.setConsumerType(CONSUMER_TYPE);
            b.setConsumerId(consumerId);
            b.setStatus("ENABLE");
            b.setDeleteFlag("NOT_DELETE");
            b.setSyncState("error");
            b.setLastError(StrUtil.maxLength(err, 500));
            b.setLastSyncAt(new Date());
            bindingMapper.insert(b);
        } else {
            b.setSyncState("error");
            b.setLastError(StrUtil.maxLength(err, 500));
            b.setLastSyncAt(new Date());
            bindingMapper.updateById(b);
        }
    }

    private static Map<String, Object> defaultPool() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("maximumPoolSize", 10);
        p.put("minimumIdle", 5);
        p.put("maxLifetime", 3600000);
        p.put("connectionTimeout", 60000);
        p.put("idleTimeout", 60000);
        return p;
    }

    private static SqlrestTypeMapping mapType(String portalType) {
        return switch (portalType) {
            case "mysql", "mariadb" -> new SqlrestTypeMapping("MYSQL", "com.mysql.jdbc.Driver");
            case "pg", "postgresql" -> new SqlrestTypeMapping("POSTGRESQL", "org.postgresql.Driver");
            case "oracle" -> new SqlrestTypeMapping("ORACLE", "oracle.jdbc.driver.OracleDriver");
            case "sqlserver" -> new SqlrestTypeMapping("SQLSERVER", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
            case "clickhouse" -> new SqlrestTypeMapping("CLICKHOUSE", "com.clickhouse.jdbc.ClickHouseDriver");
            case "doris", "starrocks" -> new SqlrestTypeMapping("DORIS", "com.mysql.jdbc.Driver");
            case "hive" -> new SqlrestTypeMapping("HIVE", "org.apache.hive.jdbc.HiveDriver");
            case "mongodb" -> new SqlrestTypeMapping("MONGODB", "com.gitee.jdbc.mongodb.JdbcDriver");
            case "elasticsearch" -> new SqlrestTypeMapping("ELASTICSEARCH", "com.gitee.esdriver.driver.EsDriver");
            case "oceanbase" -> new SqlrestTypeMapping("OCEANBASE", "com.oceanbase.jdbc.Driver");
            default -> throw new CommonException("无法映射到 SQLREST 类型: " + portalType);
        };
    }

    private static String firstNonBlank(Map<String, Object> secret, String... keys) {
        for (String k : keys) {
            String v = str(secret.get(k));
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Long extractDatasourceId(Map<String, Object> srResp) {
        if (srResp == null || !Boolean.TRUE.equals(srResp.get("ok")) || srResp.get("data") == null) {
            return null;
        }
        Object data = srResp.get("data");
        if (data instanceof Number) {
            return ((Number) data).longValue();
        }
        if (data instanceof Map<?, ?>) {
            Object id = ((Map<?, ?>) data).get("id");
            if (id != null) {
                return parseLong(String.valueOf(id));
            }
        }
        return null;
    }

    private static Long parseLong(String s) {
        if (StrUtil.isBlank(s) || "pending".equals(s)) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record SqlrestTypeMapping(String sqlrestType, String driverClass) {
    }
}
