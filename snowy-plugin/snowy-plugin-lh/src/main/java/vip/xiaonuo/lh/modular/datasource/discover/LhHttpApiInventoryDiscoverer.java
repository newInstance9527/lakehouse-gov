package vip.xiaonuo.lh.modular.datasource.discover;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HTTP API / Tableau / Superset / Airflow：优先解析 OpenAPI Spec paths；否则回落接口清单摘要。
 */
@Slf4j
@Component
@Order(10)
public class LhHttpApiInventoryDiscoverer implements LhInventoryDiscoverer {

    private static final Set<String> TYPES = Set.of(
            "http_api", "tableau", "superset", "airflow"
    );
    private static final int MAX_PATHS = 300;

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public boolean supports(String typeCode) {
        return TYPES.contains(StrUtil.blankToDefault(typeCode, "").toLowerCase(Locale.ROOT));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.ENDPOINT;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String type = StrUtil.blankToDefault(ds.getType(), "http_api").toLowerCase(Locale.ROOT);
        String specUrl = first(secret, "openapiSpec", "openApiSpec", "swaggerUrl", "specUrl");
        List<LhRemoteInventoryItem> fromSpec = new ArrayList<>();
        if (StrUtil.isNotBlank(specUrl)) {
            try {
                fromSpec = parseOpenApi(specUrl, type);
            } catch (Exception e) {
                log.warn("OpenAPI parse soft-fail {}: {}", specUrl, e.getMessage());
            }
        }
        List<LhRemoteInventoryItem> fromSummary = parseSummary(
                StrUtil.blankToDefault(ds.getSchemaSummary(), first(secret, "schema")), type);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<LhRemoteInventoryItem> items = new ArrayList<>();
        for (LhRemoteInventoryItem m : fromSpec) {
            if (seen.add(m.name)) {
                items.add(m);
            }
        }
        for (LhRemoteInventoryItem m : fromSummary) {
            if (seen.add(m.name)) {
                items.add(m);
            }
        }
        if (!fromSpec.isEmpty()) {
            return LhInventoryDiscoverResult.remote("openapi", LhInventoryObjectKinds.ENDPOINT, items);
        }
        return LhInventoryDiscoverResult.manual(
                LhInventoryObjectKinds.ENDPOINT,
                "未配置或无法解析 openapiSpec，已回落接口清单摘要；可填写 OpenAPI Spec URL 自动列举 paths",
                items);
    }

    private static List<LhRemoteInventoryItem> parseOpenApi(String specUrl, String type) {
        String url = specUrl.trim();
        if (!url.startsWith("http")) {
            url = "https://" + url;
        }
        HttpResponse resp = HttpRequest.get(url).timeout(15000).execute();
        if (!resp.isOk()) {
            throw new IllegalStateException("HTTP " + resp.getStatus());
        }
        JSONObject root = JSONUtil.parseObj(resp.body());
        JSONObject paths = root.getJSONObject("paths");
        List<LhRemoteInventoryItem> items = new ArrayList<>();
        if (paths == null) {
            return items;
        }
        for (String path : paths.keySet()) {
            if (StrUtil.isBlank(path)) {
                continue;
            }
            Object node = paths.get(path);
            if (node instanceof JSONObject methods) {
                for (String method : methods.keySet()) {
                    if (!isHttpMethod(method)) {
                        continue;
                    }
                    LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                    m.name = method.toUpperCase(Locale.ROOT) + " " + path;
                    m.engine = type;
                    m.encoding = "UTF-8";
                    m.comment = "openapi";
                    items.add(m);
                    if (items.size() >= MAX_PATHS) {
                        return items;
                    }
                }
            } else {
                LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                m.name = path;
                m.engine = type;
                m.comment = "openapi";
                items.add(m);
                if (items.size() >= MAX_PATHS) {
                    return items;
                }
            }
        }
        return items;
    }

    private static boolean isHttpMethod(String m) {
        String s = StrUtil.blankToDefault(m, "").toLowerCase(Locale.ROOT);
        return s.equals("get") || s.equals("post") || s.equals("put") || s.equals("delete")
                || s.equals("patch") || s.equals("head") || s.equals("options");
    }

    private static List<LhRemoteInventoryItem> parseSummary(String summary, String type) {
        List<LhRemoteInventoryItem> items = new ArrayList<>();
        if (StrUtil.isBlank(summary)) {
            return items;
        }
        for (String part : summary.split("[,，;；\\n\\r]+")) {
            String n = part.trim();
            if (n.isEmpty()) {
                continue;
            }
            LhRemoteInventoryItem m = new LhRemoteInventoryItem();
            m.name = n;
            m.engine = type;
            m.comment = "manual:endpoint";
            items.add(m);
        }
        return items;
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
