package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 探测 Elasticsearch / OpenSearch 是否具备 SQL 接口，供构建工作台推荐引擎。
 */
@Component
public class LhEsCapabilityProbe {

    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhProperties lhProperties;

    public Map<String, Object> probe(String portalDsId) {
        if (StrUtil.isBlank(portalDsId)) {
            throw new CommonException("需要数据源 id");
        }
        LhDatasource ds = datasourceMapper.selectById(portalDsId);
        if (ds == null || !"NOT_DELETE".equals(ds.getDeleteFlag())) {
            throw new CommonException("数据源不存在: {}", portalDsId);
        }
        String type = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        if (!"elasticsearch".equals(type) && !"opensearch".equals(type) && !"es".equals(type)) {
            throw new CommonException("仅 Elasticsearch 类数据源可探测 SQL 能力，当前 type={}", ds.getType());
        }

        Map<String, Object> secret = new LinkedHashMap<>(vaultClient.readOrEmpty(ds.getVaultPath()));
        if (StrUtil.isBlank(str(secret.get("host"))) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            secret.putIfAbsent("host", ds.getEndpointHost());
        }
        if (StrUtil.isBlank(str(secret.get("port"))) && StrUtil.isNotBlank(ds.getEndpointPort())) {
            secret.putIfAbsent("port", ds.getEndpointPort());
        }
        boolean insecure = lhProperties.getHttp() != null && lhProperties.getHttp().isInsecureSsl();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dsId", portalDsId);
        out.put("type", ds.getType());

        return LhEsConnSupport.withFirstHealthy(secret, insecure, base -> {
            out.put("base", base);
            // 1) ES _sql
            ProbeHit hit = trySql(base, "/_sql?format=json", secret, insecure);
            if (hit.ok()) {
                out.put("sqlSupported", true);
                out.put("sqlEndpoint", "/_sql");
                out.put("recommendEngine", "SQL");
                out.put("message", "已探测到 Elasticsearch SQL（/_sql）");
                out.put("detail", hit.bodyPreview());
                return out;
            }
            // 2) OpenSearch SQL 插件
            hit = trySql(base, "/_plugins/_sql", secret, insecure);
            if (hit.ok()) {
                out.put("sqlSupported", true);
                out.put("sqlEndpoint", "/_plugins/_sql");
                out.put("recommendEngine", "SQL");
                out.put("message", "已探测到 OpenSearch SQL（/_plugins/_sql）");
                out.put("detail", hit.bodyPreview());
                return out;
            }
            // 3) _xpack features（部分发行版）
            ProbeHit xpack = tryGet(base, "/_xpack", secret, insecure);
            if (xpack.status() == 200 && xpack.body() != null && xpack.body().toLowerCase(Locale.ROOT).contains("\"sql\"")) {
                out.put("sqlSupported", true);
                out.put("sqlEndpoint", "_xpack");
                out.put("recommendEngine", "SQL");
                out.put("message", "_xpack 声明含 sql 能力（建议再试跑一条 ES SQL 确认）");
                return out;
            }
            out.put("sqlSupported", false);
            out.put("recommendEngine", "ES_DSL");
            out.put("message", "未探测到 SQL 接口，建议使用 Query DSL（ES_DSL）");
            out.put("sqlProbeError", StrUtil.maxLength(
                    StrUtil.blankToDefault(hit.error(), xpack.error()), 200));
            return out;
        });
    }

    private static ProbeHit trySql(String base, String path, Map<String, Object> conn, boolean insecure) {
        try {
            HttpResponse resp = LhEsConnSupport.post(
                    base, path, "{\"query\":\"SELECT 1 AS ok\"}", conn, insecure);
            return ProbeHit.ok(resp.getStatus(), resp.body());
        } catch (Exception e) {
            return ProbeHit.fail(-1, e.getMessage());
        }
    }

    private static ProbeHit tryGet(String base, String path, Map<String, Object> conn, boolean insecure) {
        try {
            HttpResponse resp = LhEsConnSupport.get(base, path, conn, insecure);
            return ProbeHit.ok(resp.getStatus(), resp.body());
        } catch (Exception e) {
            return ProbeHit.fail(-1, e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private record ProbeHit(boolean ok, int status, String body, String error) {
        static ProbeHit ok(int status, String body) {
            return new ProbeHit(true, status, body, null);
        }

        static ProbeHit fail(int status, String error) {
            return new ProbeHit(false, status, null, error);
        }

        String bodyPreview() {
            if (StrUtil.isBlank(body)) {
                return "";
            }
            if (JSONUtil.isTypeJSON(body)) {
                try {
                    JSONObject o = JSONUtil.parseObj(body);
                    return StrUtil.maxLength(o.toString(), 120);
                } catch (Exception ignored) {
                }
            }
            return StrUtil.maxLength(body, 120);
        }
    }
}
