package vip.xiaonuo.lh.modular.datasource.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.engine.SupersetClient;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.core.ws.ExternalBindingGuard;
import vip.xiaonuo.lh.core.ws.ExternalName;
import vip.xiaonuo.lh.modular.datasource.entity.LhConsumerBinding;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceStatusEnum;
import vip.xiaonuo.lh.modular.datasource.mapper.LhConsumerBindingMapper;
import vip.xiaonuo.lh.modular.datasource.support.LhJdbcUrlBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将门户 Trino（query_gateway）投影为 Superset Database。
 * <p>硬约束：仅 trino；禁止业务库批量下发（对齐全栈 §37）。</p>
 */
@Component
public class LhDatasourceSupersetProjector {

    public static final String CONSUMER_TYPE = "superset";

    @Resource
    private SupersetClient supersetClient;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhConsumerBindingMapper bindingMapper;
    @Resource
    private ExternalBindingGuard externalBindingGuard;

    public static boolean isProjectable(LhDatasource ds) {
        if (ds == null) {
            return false;
        }
        if (!"trino".equalsIgnoreCase(StrUtil.blankToDefault(ds.getType(), ""))) {
            return false;
        }
        String purposes = StrUtil.blankToDefault(ds.getPurposes(), "");
        return purposes.contains("query_gateway");
    }

    public Map<String, Object> project(LhDatasource ds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dsId", ds.getId());
        result.put("dsCode", ds.getDsCode());
        result.put("name", ds.getName());
        result.put("consumerType", CONSUMER_TYPE);

        if (!supersetClient.configured()) {
            result.put("ok", false);
            result.put("skipped", true);
            result.put("message", "未配置 lh.superset.url，跳过投影");
            return result;
        }
        if (LhDatasourceStatusEnum.REVOKED.getValue().equals(ds.getStatus())
                || LhDatasourceStatusEnum.PAUSED.getValue().equals(ds.getStatus())) {
            result.put("ok", false);
            result.put("skipped", true);
            result.put("message", "数据源已停用/吊销，跳过投影");
            markRevoked(ds.getId());
            return result;
        }
        if (!isProjectable(ds)) {
            result.put("ok", false);
            result.put("skipped", true);
            result.put("message", "仅投影 type=trino 且 purposes 含 query_gateway（禁止业务库直连 Superset）");
            return result;
        }

        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String sqlalchemyUri = buildSqlalchemyUri(ds, secret);
        if (StrUtil.isBlank(sqlalchemyUri)) {
            String tip = "无法拼装 Trino sqlalchemy_uri（检查 Vault host/port/jdbcUrl）";
            result.put("ok", false);
            result.put("message", tip);
            upsertBindingError(ds.getId(), tip);
            return result;
        }

        String ws = StrUtil.blankToDefault(ds.getWs(), "default");
        String code = StrUtil.blankToDefault(ds.getDsCode(), ds.getId());
        String dbName = ExternalName.of(ws, code);
        String extId = ExternalName.extId(ExternalName.KIND_SUPERSET_DB, ws, code);
        LhConsumerBinding existing = findBinding(ds.getId());
        externalBindingGuard.assertConsumerExtIdFree(extId, existing == null ? null : existing.getId());

        Long ssId = null;
        if (existing != null && StrUtil.isNotBlank(existing.getProjection())) {
            ssId = JSONUtil.parseObj(existing.getProjection()).getLong("supersetDatabaseId");
        }

        Map<String, Object> ssResp = supersetClient.upsertDatabase(ssId, dbName, sqlalchemyUri, null);
        boolean ok = Boolean.TRUE.equals(ssResp.get("ok"));
        Long databaseId = ssResp.get("id") instanceof Number n ? n.longValue() : null;
        result.put("ok", ok);
        result.put("degraded", Boolean.TRUE.equals(ssResp.get("degraded")));
        result.put("extId", extId);
        result.put("superset", ssResp);
        result.put("supersetDatabaseId", databaseId);
        result.put("supersetDatabaseName", dbName);

        if (ok && databaseId != null) {
            upsertBindingOk(ds.getId(), databaseId, dbName, extId, sqlalchemyUriMasked(sqlalchemyUri));
        } else {
            upsertBindingError(ds.getId(), String.valueOf(ssResp.get("message")));
            if (result.get("message") == null) {
                result.put("message", ssResp.get("message"));
            }
        }
        return result;
    }

    public Long resolveSupersetDatabaseId(String portalDsId) {
        LhConsumerBinding b = findBinding(portalDsId);
        if (b != null && "synced".equalsIgnoreCase(b.getSyncState()) && StrUtil.isNotBlank(b.getProjection())) {
            return JSONUtil.parseObj(b.getProjection()).getLong("supersetDatabaseId");
        }
        return null;
    }

    public List<LhConsumerBinding> listBindings() {
        return bindingMapper.selectList(new QueryWrapper<LhConsumerBinding>().lambda()
                .eq(LhConsumerBinding::getConsumerType, CONSUMER_TYPE));
    }

    private String buildSqlalchemyUri(LhDatasource ds, Map<String, Object> secret) {
        String jdbc = str(secret.get("jdbcUrl"));
        if (StrUtil.isBlank(jdbc)) {
            jdbc = LhJdbcUrlBuilder.buildFromSecret(
                    "trino", secret, ds.getEndpointHost(), ds.getEndpointPort(), ds.getDatabaseName());
        }
        String host = firstNonBlank(str(secret.get("host")), ds.getEndpointHost());
        String port = firstNonBlank(str(secret.get("port")),
                ds.getEndpointPort() == null ? null : String.valueOf(ds.getEndpointPort()), "8080");
        String catalog = firstNonBlank(str(secret.get("catalog")), str(secret.get("database")),
                ds.getDatabaseName(), "iceberg");
        String user = firstNonBlank(str(secret.get("username")), str(secret.get("user")), "trino");
        String password = firstNonBlank(str(secret.get("password")), "");

        if (StrUtil.isNotBlank(jdbc) && jdbc.toLowerCase(Locale.ROOT).startsWith("jdbc:trino://")) {
            // jdbc:trino://host:port/catalog[/schema] → trino://user:pass@host:port/catalog
            String rest = jdbc.substring("jdbc:trino://".length());
            int slash = rest.indexOf('/');
            String hp = slash >= 0 ? rest.substring(0, slash) : rest;
            String path = slash >= 0 ? rest.substring(slash + 1) : catalog;
            if (StrUtil.isNotBlank(path)) {
                catalog = path.split("/")[0];
            }
            if (hp.contains(":")) {
                host = hp.substring(0, hp.indexOf(':'));
                port = hp.substring(hp.indexOf(':') + 1);
            } else if (StrUtil.isNotBlank(hp)) {
                host = hp;
            }
        }
        if (StrUtil.isBlank(host)) {
            return null;
        }
        String encUser = urlEnc(user);
        String encPass = urlEnc(password);
        if (StrUtil.isNotBlank(password)) {
            return "trino://" + encUser + ":" + encPass + "@" + host + ":" + port + "/" + catalog;
        }
        return "trino://" + encUser + "@" + host + ":" + port + "/" + catalog;
    }

    private static String sqlalchemyUriMasked(String uri) {
        if (StrUtil.isBlank(uri)) {
            return uri;
        }
        // trino://user:pass@host → trino://user:***@host
        int at = uri.indexOf('@');
        int scheme = uri.indexOf("://");
        if (at > 0 && scheme >= 0) {
            String head = uri.substring(0, scheme + 3);
            String cred = uri.substring(scheme + 3, at);
            int colon = cred.indexOf(':');
            if (colon > 0) {
                return head + cred.substring(0, colon) + ":***@" + uri.substring(at + 1);
            }
        }
        return uri;
    }

    private LhConsumerBinding findBinding(String dsId) {
        return bindingMapper.selectOne(new QueryWrapper<LhConsumerBinding>().lambda()
                .eq(LhConsumerBinding::getDsId, dsId)
                .eq(LhConsumerBinding::getConsumerType, CONSUMER_TYPE)
                .last("LIMIT 1"));
    }

    private void upsertBindingOk(String dsId, Long databaseId, String dbName, String extId, String uriMasked) {
        LhConsumerBinding b = findBinding(dsId);
        Map<String, Object> proj = new LinkedHashMap<>();
        proj.put("supersetDatabaseId", databaseId);
        proj.put("supersetDatabaseName", dbName);
        proj.put("sqlalchemyUriMasked", uriMasked);
        proj.put("extId", extId);
        String consumerId = "ds:" + dsId;
        if (b == null) {
            b = new LhConsumerBinding();
            b.setId(IdUtil.getSnowflakeNextId());
            b.setRevision(1);
            b.setDsId(dsId);
            b.setConsumerType(CONSUMER_TYPE);
            b.setConsumerId(consumerId);
            b.setExtId(extId);
            b.setStatus("ENABLE");
            b.setDeleteFlag("NOT_DELETE");
            b.setProjection(JSONUtil.toJsonStr(proj));
            b.setSyncState("synced");
            b.setLastSyncAt(new Date());
            b.setLastError(null);
            bindingMapper.insert(b);
        } else {
            b.setConsumerId(consumerId);
            b.setExtId(extId);
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

    private void markRevoked(String dsId) {
        LhConsumerBinding b = findBinding(dsId);
        if (b == null) {
            return;
        }
        b.setSyncState("revoked");
        b.setLastSyncAt(new Date());
        b.setRevision(b.getRevision() == null ? 1 : b.getRevision() + 1);
        bindingMapper.updateById(b);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String urlEnc(String s) {
        if (s == null) {
            return "";
        }
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
