package vip.xiaonuo.lh.modular.dataapi.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import vip.xiaonuo.lh.core.engine.SqlrestClient;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从门户绑定 +（可选）SQLREST detail 拼装 OpenAPI 3.0 文档（含鉴权、入参、出参、示例、错误码）。
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
        info.put("description", """
                ## 概述
                由湖仓门户「数据服务」聚合导出。调用方经 **SQLREST Gateway** 访问已发布接口。

                ## 鉴权
                1. 在申请中心提交「API 调用申请」
                2. 审批通过后签发订阅 Key
                3. 请求头同时携带：
                   - `X-App-Key: <AppKey>`
                   - `Authorization: Bearer <Secret>`

                ## 统一响应（format=wrapped 时）
                ```json
                { "code": 200, "message": "ok", "data": ... }
                ```
                `data` 形态取决于响应 shape：`list` 数组 / `object` 单行 / `page` 分页对象。

                ## 错误码
                | HTTP | 说明 |
                |------|------|
                | 400 | 参数校验失败 |
                | 401 | 缺少或无效订阅 Key |
                | 403 | 无调用权限 |
                | 404 | 接口不存在或未发布 |
                | 429 | 触发限流 |
                | 500 | 服务端执行失败 |
                """);
        info.put("contact", Map.of(
                "name", "Lakehouse Data API",
                "url", "https://lakehouse.local/dataservice"));
        doc.put("info", info);

        List<Map<String, Object>> servers = new ArrayList<>();
        String base = StrUtil.blankToDefault(gatewayUrl, "").replaceAll("/$", "");
        if (StrUtil.isNotBlank(base)) {
            servers.add(Map.of("url", base + "/api", "description", "SQLREST Gateway（生产调用入口）"));
            servers.add(Map.of("url", base, "description", "Gateway 根地址"));
        } else {
            servers.add(Map.of("url", "/api", "description", "相对路径（请替换为实际 Gateway）"));
        }
        doc.put("servers", servers);

        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> schemes = new LinkedHashMap<>();
        schemes.put("AppKey", Map.of(
                "type", "apiKey",
                "in", "header",
                "name", "X-App-Key",
                "description", "订阅签发的 AppKey（申请中心通过后下发）"));
        schemes.put("BearerToken", Map.of(
                "type", "http",
                "scheme", "bearer",
                "bearerFormat", "Secret",
                "description", "订阅签发的 Secret；请求头 `Authorization: Bearer <Secret>`"));
        components.put("securitySchemes", schemes);

        Map<String, Object> schemas = new LinkedHashMap<>();
        schemas.put("ErrorBody", Map.of(
                "type", "object",
                "properties", Map.of(
                        "code", Map.of("type", "integer", "example", 401),
                        "message", Map.of("type", "string", "example", "unauthorized"),
                        "data", Map.of("nullable", true))));
        schemas.put("WrappedList", Map.of(
                "type", "object",
                "properties", Map.of(
                        "code", Map.of("type", "integer", "example", 200),
                        "message", Map.of("type", "string", "example", "ok"),
                        "data", Map.of("type", "array", "items", Map.of("type", "object")))));
        schemas.put("WrappedPage", Map.of(
                "type", "object",
                "properties", Map.of(
                        "code", Map.of("type", "integer", "example", 200),
                        "message", Map.of("type", "string", "example", "ok"),
                        "data", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "total", Map.of("type", "integer"),
                                        "page", Map.of("type", "integer"),
                                        "size", Map.of("type", "integer"),
                                        "records", Map.of("type", "array", "items", Map.of("type", "object")))))));
        components.put("schemas", schemas);
        doc.put("components", components);
        doc.put("security", List.of(
                Map.of("AppKey", List.of(), "BearerToken", List.of())));

        Set<String> tagNames = new LinkedHashSet<>();
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
                Map<String, Object> sqlrest = sqlrestByApiId == null
                        ? null
                        : sqlrestByApiId.get(b.getSqlrestApiId());
                Map<String, Object> op = operationOf(b, sqlrest, base);
                @SuppressWarnings("unchecked")
                List<String> opTags = (List<String>) op.get("tags");
                if (opTags != null) {
                    tagNames.addAll(opTags);
                }
                pathItem.put(method, op);
            }
        }
        doc.put("paths", paths);

        if (!tagNames.isEmpty()) {
            List<Map<String, Object>> tagDefs = new ArrayList<>();
            for (String t : tagNames) {
                tagDefs.add(Map.of("name", t, "description", "业务域 / 来源：" + t));
            }
            doc.put("tags", tagDefs);
        }

        doc.put("x-lakehouse", Map.of(
                "edge", "gateway",
                "auth", "X-App-Key + Bearer",
                "bindingCount", bindings == null ? 0 : bindings.size(),
                "docVersion", "detailed-1"));
        return doc;
    }

    private static Map<String, Object> operationOf(DataapiApiBinding b,
                                                   Map<String, Object> sqlrest,
                                                   String gatewayBase) {
        Map<String, Object> op = new LinkedHashMap<>();
        String name = StrUtil.blankToDefault(b.getName(), b.getPublicPath());
        op.put("summary", name);
        op.put("operationId", "api_" + StrUtil.blankToDefault(b.getId(), b.getPublicPath())
                .replaceAll("[^A-Za-z0-9_]", "_"));

        String format = "wrapped";
        String shape = "list";
        JSONArray responseFields = null;
        if (StrUtil.isNotBlank(b.getResponseJson())) {
            try {
                JSONObject rj = JSONUtil.parseObj(b.getResponseJson());
                format = StrUtil.blankToDefault(rj.getStr("format"), format);
                shape = StrUtil.blankToDefault(rj.getStr("shape"), shape);
                Object fields = rj.get("fields");
                if (fields instanceof JSONArray arr) {
                    responseFields = arr;
                }
            } catch (Exception ignored) {
                /* soft */
            }
        }

        StringBuilder desc = new StringBuilder();
        desc.append("### ").append(name).append("\n\n");
        if (StrUtil.isNotBlank(b.getRemark())) {
            desc.append(b.getRemark().trim()).append("\n\n");
        }
        desc.append("| 项 | 值 |\n|---|---|\n");
        desc.append("| 路径 | `").append(b.getPublicPath()).append("` |\n");
        desc.append("| 方法 | `").append(StrUtil.blankToDefault(b.getMethod(), "GET").toUpperCase(Locale.ROOT))
                .append("` |\n");
        desc.append("| 状态 | `").append(StrUtil.blankToDefault(b.getState(), "—")).append("` |\n");
        desc.append("| 环境 | ").append(StrUtil.blankToDefault(b.getPublishEnv(), "—")).append(" |\n");
        desc.append("| 业务域 | ").append(StrUtil.blankToDefault(b.getDomainCode(), "—")).append(" |\n");
        desc.append("| 来源 | ").append(StrUtil.blankToDefault(b.getSourceKind(), "sql"));
        if (StrUtil.isNotBlank(b.getSourceRef())) {
            desc.append(" / `").append(b.getSourceRef()).append("`");
        }
        desc.append(" |\n");
        desc.append("| 鉴权 | ").append(StrUtil.blankToDefault(b.getAuthMode(), "Token（X-App-Key + Bearer）"))
                .append(" |\n");
        int qps = b.getQpsLimit() == null ? 100 : b.getQpsLimit();
        int burst = b.getBurstLimit() == null ? qps * 2 : b.getBurstLimit();
        desc.append("| 限流 | QPS ").append(qps).append(" · Burst ").append(burst).append(" |\n");
        desc.append("| 响应封装 | format=`").append(format).append("` · shape=`").append(shape).append("` |\n");
        if (b.getSqlrestVersion() != null) {
            desc.append("| 接口版本 | v").append(b.getSqlrestVersion()).append(" |\n");
        }
        desc.append("\n#### 调用示例（curl）\n\n```bash\n");
        desc.append(curlSample(b, gatewayBase));
        desc.append("\n```\n");
        op.put("description", desc.toString());

        List<String> tags = new ArrayList<>();
        if (StrUtil.isNotBlank(b.getDomainCode())) {
            tags.add(b.getDomainCode());
        }
        if (StrUtil.isNotBlank(b.getSourceKind())) {
            tags.add(b.getSourceKind());
        }
        List<String> customTags = parseTags(b.getTagsJson());
        tags.addAll(customTags);
        if (!tags.isEmpty()) {
            op.put("tags", tags.stream().distinct().toList());
        }

        List<Map<String, Object>> parameters = new ArrayList<>();
        List<Map<String, Object>> bodyProps = new ArrayList<>();
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
                String in = locationToIn(p.getStr("location"), b.getMethod());
                Map<String, Object> schema = paramSchema(p);
                if ("body".equals(in)) {
                    bodyProps.add(Map.of(
                            "name", p.getStr("name"),
                            "required", Boolean.TRUE.equals(p.getBool("required")),
                            "schema", schema,
                            "description", StrUtil.blankToDefault(p.getStr("remark"),
                                    StrUtil.blankToDefault(p.getStr("desc"), ""))));
                    continue;
                }
                Map<String, Object> param = new LinkedHashMap<>();
                param.put("name", p.getStr("name"));
                param.put("in", in);
                param.put("required", Boolean.TRUE.equals(p.getBool("required")) || "path".equals(in));
                String pDesc = StrUtil.blankToDefault(p.getStr("remark"), p.getStr("desc"));
                if (StrUtil.isNotBlank(pDesc)) {
                    param.put("description", pDesc);
                }
                if (p.get("example") != null) {
                    param.put("example", p.get("example"));
                } else if (p.get("defaultValue") != null) {
                    param.put("example", p.get("defaultValue"));
                }
                param.put("schema", schema);
                parameters.add(param);
            }
        }
        if (!parameters.isEmpty()) {
            op.put("parameters", parameters);
        }

        String method = StrUtil.blankToDefault(b.getMethod(), "GET").toUpperCase(Locale.ROOT);
        if (!bodyProps.isEmpty() || "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            Map<String, Object> bodySchema = new LinkedHashMap<>();
            bodySchema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            Map<String, Object> example = new LinkedHashMap<>();
            for (Map<String, Object> bp : bodyProps) {
                String pn = String.valueOf(bp.get("name"));
                @SuppressWarnings("unchecked")
                Map<String, Object> sch = new LinkedHashMap<>((Map<String, Object>) bp.get("schema"));
                if (bp.get("description") != null && StrUtil.isNotBlank(String.valueOf(bp.get("description")))) {
                    sch.put("description", bp.get("description"));
                }
                props.put(pn, sch);
                if (Boolean.TRUE.equals(bp.get("required"))) {
                    required.add(pn);
                }
                example.put(pn, sch.getOrDefault("default", sch.getOrDefault("example", "")));
            }
            if (!props.isEmpty()) {
                bodySchema.put("properties", props);
            } else {
                bodySchema.put("additionalProperties", true);
            }
            if (!required.isEmpty()) {
                bodySchema.put("required", required);
            }
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("schema", bodySchema);
            if (!example.isEmpty()) {
                media.put("example", example);
            }
            String ct = StrUtil.blankToDefault(b.getContentType(), "application/json");
            op.put("requestBody", Map.of(
                    "required", !required.isEmpty(),
                    "content", Map.of(ct, media)));
        }

        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("200", successResponse(format, shape, responseFields, name));
        responses.put("400", Map.of(
                "description", "参数校验失败",
                "content", Map.of("application/json", Map.of(
                        "schema", Map.of("$ref", "#/components/schemas/ErrorBody"),
                        "example", Map.of("code", 400, "message", "bad request", "data", null)))));
        responses.put("401", Map.of(
                "description", "未授权：缺少有效订阅 Key（X-App-Key / Bearer）",
                "content", Map.of("application/json", Map.of(
                        "schema", Map.of("$ref", "#/components/schemas/ErrorBody"),
                        "example", Map.of("code", 401, "message", "unauthorized", "data", null)))));
        responses.put("403", Map.of("description", "无调用权限或订阅已过期"));
        responses.put("404", Map.of("description", "接口不存在或未发布"));
        responses.put("429", Map.of(
                "description", "触发限流（QPS " + qps + " / Burst " + burst + "）",
                "content", Map.of("application/json", Map.of(
                        "schema", Map.of("$ref", "#/components/schemas/ErrorBody"),
                        "example", Map.of("code", 429, "message", "too many requests", "data", null)))));
        responses.put("500", Map.of(
                "description", "服务端执行失败",
                "content", Map.of("application/json", Map.of(
                        "schema", Map.of("$ref", "#/components/schemas/ErrorBody")))));
        op.put("responses", responses);

        List<Map<String, Object>> samples = new ArrayList<>();
        samples.add(Map.of(
                "lang", "curl",
                "label", "cURL",
                "source", curlSample(b, gatewayBase)));
        op.put("x-codeSamples", samples);
        op.put("x-lakehouse", Map.of(
                "bindingId", b.getId(),
                "state", StrUtil.blankToDefault(b.getState(), ""),
                "qps", qps,
                "burst", burst,
                "responseFormat", format,
                "responseShape", shape,
                "authMode", StrUtil.blankToDefault(b.getAuthMode(), "token")));
        return op;
    }

    private static Map<String, Object> successResponse(String format, String shape,
                                                       JSONArray fields, String name) {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("description", "成功 · " + name + "（format=" + format + ", shape=" + shape + "）");
        Map<String, Object> dataSchema = rowSchema(fields);
        Map<String, Object> rootSchema;
        // origin+list 时 example 为数组，其余为对象
        Object example;
        if ("nil".equalsIgnoreCase(format)) {
            rootSchema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "code", Map.of("type", "integer", "example", 200),
                            "message", Map.of("type", "string", "example", "ok")));
            example = Map.of("code", 200, "message", "ok");
        } else if ("origin".equalsIgnoreCase(format)) {
            if ("object".equalsIgnoreCase(shape)) {
                rootSchema = dataSchema;
                example = sampleRow(fields);
            } else if ("page".equalsIgnoreCase(shape)) {
                rootSchema = Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "total", Map.of("type", "integer"),
                                "records", Map.of("type", "array", "items", dataSchema)));
                example = Map.of("total", 1, "records", List.of(sampleRow(fields)));
            } else {
                rootSchema = Map.of("type", "array", "items", dataSchema);
                example = List.of(sampleRow(fields));
            }
        } else {
            // wrapped
            if ("object".equalsIgnoreCase(shape)) {
                rootSchema = Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "code", Map.of("type", "integer", "example", 200),
                                "message", Map.of("type", "string", "example", "ok"),
                                "data", dataSchema));
                example = Map.of("code", 200, "message", "ok", "data", sampleRow(fields));
            } else if ("page".equalsIgnoreCase(shape)) {
                rootSchema = Map.of("$ref", "#/components/schemas/WrappedPage");
                example = Map.of(
                        "code", 200,
                        "message", "ok",
                        "data", Map.of(
                                "total", 1,
                                "page", 1,
                                "size", 20,
                                "records", List.of(sampleRow(fields))));
            } else {
                rootSchema = Map.of("$ref", "#/components/schemas/WrappedList");
                example = Map.of("code", 200, "message", "ok", "data", List.of(sampleRow(fields)));
            }
        }
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("schema", rootSchema);
        media.put("example", example);
        ok.put("content", Map.of("application/json", media));
        return ok;
    }

    private static Map<String, Object> rowSchema(JSONArray fields) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        if (fields == null || fields.isEmpty()) {
            schema.put("additionalProperties", true);
            return schema;
        }
        Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            JSONObject f = fields.getJSONObject(i);
            if (f == null) {
                continue;
            }
            String out = StrUtil.blankToDefault(f.getStr("name"), f.getStr("source"));
            if (StrUtil.isBlank(out)) {
                continue;
            }
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("type", typeToOas(f.getStr("type")));
            String src = f.getStr("source");
            String tip = StrUtil.blankToDefault(f.getStr("desc"), f.getStr("remark"));
            StringBuilder d = new StringBuilder();
            if (StrUtil.isNotBlank(src) && !src.equals(out)) {
                d.append("SQL 列 `").append(src).append("`");
            }
            if (StrUtil.isNotBlank(f.getStr("transform")) && !"none".equalsIgnoreCase(f.getStr("transform"))) {
                if (!d.isEmpty()) {
                    d.append(" · ");
                }
                d.append("转换 ").append(f.getStr("transform"));
            }
            if (StrUtil.isNotBlank(tip)) {
                if (!d.isEmpty()) {
                    d.append(" · ");
                }
                d.append(tip);
            }
            if (!d.isEmpty()) {
                ps.put("description", d.toString());
            }
            props.put(out, ps);
        }
        if (!props.isEmpty()) {
            schema.put("properties", props);
        } else {
            schema.put("additionalProperties", true);
        }
        return schema;
    }

    private static Map<String, Object> sampleRow(JSONArray fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (fields == null || fields.isEmpty()) {
            row.put("id", 1);
            row.put("name", "示例");
            return row;
        }
        for (int i = 0; i < fields.size(); i++) {
            JSONObject f = fields.getJSONObject(i);
            if (f == null) {
                continue;
            }
            String out = StrUtil.blankToDefault(f.getStr("name"), f.getStr("source"));
            if (StrUtil.isBlank(out)) {
                continue;
            }
            String t = typeToOas(f.getStr("type"));
            Object sample = switch (t) {
                case "number" -> 1;
                case "boolean" -> true;
                default -> "示例";
            };
            if (f.get("example") != null) {
                sample = f.get("example");
            }
            row.put(out, sample);
        }
        return row;
    }

    private static Map<String, Object> paramSchema(JSONObject p) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(p.getBool("isArray"))) {
            schema.put("type", "array");
            schema.put("items", Map.of("type", typeToOas(p.getStr("type"))));
        } else {
            schema.put("type", typeToOas(p.getStr("type")));
        }
        if (p.get("defaultValue") != null) {
            schema.put("default", p.get("defaultValue"));
        }
        if (p.get("example") != null) {
            schema.put("example", p.get("example"));
        }
        return schema;
    }

    private static String curlSample(DataapiApiBinding b, String gatewayBase) {
        String base = StrUtil.blankToDefault(gatewayBase, "https://gateway.example.com").replaceAll("/$", "");
        String path = SqlrestClient.toSqlrestPath(b.getPublicPath());
        String method = StrUtil.blankToDefault(b.getMethod(), "GET").toUpperCase(Locale.ROOT);
        String url = base + "/api/" + path;
        StringBuilder sb = new StringBuilder();
        sb.append("curl -X ").append(method).append(" '").append(url).append("' \\\n");
        sb.append("  -H 'X-App-Key: <YOUR_APP_KEY>' \\\n");
        sb.append("  -H 'Authorization: Bearer <YOUR_SECRET>'");
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            String ct = StrUtil.blankToDefault(b.getContentType(), "application/json");
            sb.append(" \\\n  -H 'Content-Type: ").append(ct).append("'");
            if (ct.contains("json")) {
                sb.append(" \\\n  -d '{}'");
            }
        }
        return sb.toString();
    }

    private static List<String> parseTags(String tagsJson) {
        if (StrUtil.isBlank(tagsJson)) {
            return List.of();
        }
        try {
            JSONArray arr = JSONUtil.parseArray(tagsJson);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                String t = arr.getStr(i);
                if (StrUtil.isNotBlank(t)) {
                    out.add(t.trim());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String locationToIn(String location, String method) {
        String loc = StrUtil.blankToDefault(location, "").toUpperCase(Locale.ROOT);
        if (loc.contains("HEADER")) {
            return "header";
        }
        if (loc.contains("PATH")) {
            return "path";
        }
        if (loc.contains("BODY")) {
            return "body";
        }
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            // 未标明 location 的写方法参数默认 query（Gateway form 兼容）；显式 BODY 才进 requestBody
            return "query";
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
