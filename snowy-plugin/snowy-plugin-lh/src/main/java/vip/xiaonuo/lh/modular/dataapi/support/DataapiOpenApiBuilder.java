package vip.xiaonuo.lh.modular.dataapi.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import vip.xiaonuo.lh.core.engine.SqlrestClient;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从门户绑定 +（可选）SQLREST detail 拼装 OpenAPI 3.0 文档。
 */
public final class DataapiOpenApiBuilder {

    private DataapiOpenApiBuilder() {
    }

    public static Map<String, Object> build(List<DataapiApiBinding> bindings,
                                            Map<String, Map<String, Object>> sqlrestByApiId,
                                            String gatewayUrl,
                                            String title) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", StrUtil.blankToDefault(title, "Lakehouse Data API"));
        info.put("version", "1.0.0");
        info.put("description", "由门户数据服务聚合；边缘 = SQLREST Gateway；鉴权 = 平台订阅 Key（X-App-Key + Bearer）");
        doc.put("info", info);

        List<Map<String, Object>> servers = new ArrayList<>();
        String base = StrUtil.blankToDefault(gatewayUrl, "").replaceAll("/$", "");
        if (StrUtil.isNotBlank(base)) {
            servers.add(Map.of("url", base + "/api", "description", "SQLREST Gateway"));
        }
        doc.put("servers", servers);

        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> schemes = new LinkedHashMap<>();
        schemes.put("AppKey", Map.of(
                "type", "apiKey",
                "in", "header",
                "name", "X-App-Key",
                "description", "订阅签发的 AppKey"));
        schemes.put("BearerToken", Map.of(
                "type", "http",
                "scheme", "bearer",
                "description", "订阅签发的 Secret（Authorization: Bearer）"));
        components.put("securitySchemes", schemes);
        doc.put("components", components);
        doc.put("security", List.of(
                Map.of("AppKey", List.of(), "BearerToken", List.of())));

        Map<String, Object> paths = new LinkedHashMap<>();
        if (bindings != null) {
            for (DataapiApiBinding b : bindings) {
                if (b == null || StrUtil.isBlank(b.getPublicPath())) {
                    continue;
                }
                String pathKey = "/" + SqlrestClient.toSqlrestPath(b.getPublicPath());
                @SuppressWarnings("unchecked")
                Map<String, Object> pathItem = (Map<String, Object>) paths.computeIfAbsent(
                        pathKey, k -> new LinkedHashMap<>());
                String method = StrUtil.blankToDefault(b.getMethod(), "get").toLowerCase(Locale.ROOT);
                Map<String, Object> op = operationOf(b, sqlrestByApiId == null
                        ? null
                        : sqlrestByApiId.get(b.getSqlrestApiId()));
                pathItem.put(method, op);
            }
        }
        doc.put("paths", paths);
        doc.put("x-lakehouse", Map.of(
                "edge", "gateway",
                "bindingCount", bindings == null ? 0 : bindings.size()));
        return doc;
    }

    private static Map<String, Object> operationOf(DataapiApiBinding b, Map<String, Object> sqlrest) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("summary", StrUtil.blankToDefault(b.getName(), b.getPublicPath()));
        op.put("operationId", "api_" + StrUtil.blankToDefault(b.getId(), b.getPublicPath())
                .replaceAll("[^A-Za-z0-9_]", "_"));
        op.put("description", StrUtil.blankToDefault(b.getRemark(),
                "sourceKind=" + StrUtil.blankToDefault(b.getSourceKind(), "sql")
                        + (StrUtil.isNotBlank(b.getSourceRef()) ? (" sourceRef=" + b.getSourceRef()) : "")));
        List<String> tags = new ArrayList<>();
        if (StrUtil.isNotBlank(b.getDomainCode())) {
            tags.add(b.getDomainCode());
        }
        if (StrUtil.isNotBlank(b.getSourceKind()) && !"sql".equalsIgnoreCase(b.getSourceKind())) {
            tags.add(b.getSourceKind());
        }
        if (!tags.isEmpty()) {
            op.put("tags", tags);
        }

        List<Map<String, Object>> parameters = new ArrayList<>();
        Object paramsSrc = null;
        if (sqlrest != null && Boolean.TRUE.equals(sqlrest.get("ok"))) {
            Object data = sqlrest.get("data");
            if (data != null) {
                JSONObject d = JSONUtil.parseObj(data);
                paramsSrc = d.get("params");
            }
        }
        if (paramsSrc == null && StrUtil.isNotBlank(b.getParamJson())) {
            paramsSrc = JSONUtil.parseArray(b.getParamJson());
        }
        if (paramsSrc instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                JSONObject p = arr.getJSONObject(i);
                if (p == null || StrUtil.isBlank(p.getStr("name"))) {
                    continue;
                }
                Map<String, Object> param = new LinkedHashMap<>();
                param.put("name", p.getStr("name"));
                param.put("in", locationToIn(p.getStr("location"), b.getMethod()));
                param.put("required", Boolean.TRUE.equals(p.getBool("required")));
                param.put("description", StrUtil.blankToDefault(p.getStr("remark"), p.getStr("desc")));
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", typeToOas(p.getStr("type")));
                if (Boolean.TRUE.equals(p.getBool("isArray"))) {
                    schema.put("type", "array");
                    schema.put("items", Map.of("type", typeToOas(p.getStr("type"))));
                }
                if (p.get("defaultValue") != null) {
                    schema.put("default", p.get("defaultValue"));
                }
                param.put("schema", schema);
                parameters.add(param);
            }
        }
        if (!parameters.isEmpty()) {
            op.put("parameters", parameters);
        }

        Map<String, Object> responses = new LinkedHashMap<>();
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("description", "成功（SQLREST 包装或原样，取决于 responseFormat）");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("application/json", Map.of("schema", Map.of("type", "object")));
        ok.put("content", content);
        responses.put("200", ok);
        responses.put("401", Map.of("description", "未授权：缺少有效订阅 Key"));
        responses.put("429", Map.of("description", "限流"));
        op.put("responses", responses);
        return op;
    }

    private static String locationToIn(String location, String method) {
        String loc = StrUtil.blankToDefault(location, "").toUpperCase(Locale.ROOT);
        if (loc.contains("HEADER")) {
            return "header";
        }
        if (loc.contains("PATH")) {
            return "path";
        }
        if (loc.contains("BODY") || "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            // OpenAPI query/header/path only for parameters; body params → query for Gateway form 兼容
            return loc.contains("BODY") ? "query" : "query";
        }
        return "query";
    }

    private static String typeToOas(String type) {
        String t = StrUtil.blankToDefault(type, "string").toLowerCase(Locale.ROOT);
        if (t.contains("int") || t.contains("long") || t.contains("number") || t.contains("double")
                || t.contains("float") || t.contains("decimal")) {
            return "number";
        }
        if (t.contains("bool")) {
            return "boolean";
        }
        return "string";
    }
}
