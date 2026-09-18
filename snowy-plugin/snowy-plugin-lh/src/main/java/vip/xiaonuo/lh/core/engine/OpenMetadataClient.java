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
package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;
import vip.xiaonuo.lh.core.vault.LhVaultClient;

import java.util.*;

/**
 * OpenMetadata 客户端（Bot Token 来自 Vault）
 * <p>禁止使用人类登录 JWT；服务端只读 {@code platform/openmetadata/bot}。
 * Schema Sync 仅 upsert 结构字段，保留 description / owner / tags。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class OpenMetadataClient {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;
    @Resource
    private LhVaultClient vaultClient;

    /**
     * 健康 / 版本探测
     *
     * @return 状态
     */
    public Map<String, Object> health() {
        try {
            String body = authGet("/api/v1/system/version");
            return Map.of("component", "openmetadata", "status", "UP", "body", body);
        } catch (Exception e) {
            return Map.of("component", "openmetadata", "status", "DOWN", "error", e.getMessage());
        }
    }

    /**
     * 表列表（示例）
     *
     * @param limit 条数
     * @return 原始 JSON
     */
    public Map<String, Object> listTables(int limit) {
        try {
            String body = authGet("/api/v1/tables?limit=" + Math.max(1, Math.min(limit, 100)));
            return Map.of("ok", true, "data", JSONUtil.parse(body));
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("degraded", true);
            m.put("message", e.getMessage());
            return m;
        }
    }

    /**
     * 将 Bot Token 写入 Vault（运维录入 / 轮换）
     *
     * @param token Bot PAT
     * @param email 可选
     */
    public void rotateBotToken(String token, String email) {
        if (StrUtil.isBlank(token)) {
            throw new CommonException("token 不能为空");
        }
        Map<String, Object> secret = new LinkedHashMap<>();
        secret.put("token", token);
        if (StrUtil.isNotBlank(email)) {
            secret.put("email", email);
        }
        vaultClient.write(lhProperties.getOpenmetadata().getVaultPath(), secret);
    }

    /**
     * 按 FQN 取表；不存在返回 null
     */
    public JSONObject getTableByFqn(String fqn) {
        try {
            String body = authGet("/api/v1/tables/name/" + encPath(fqn) + "?fields=columns,owners,tags,description");
            return JSONUtil.parseObj(body);
        } catch (CommonException e) {
            if (StrUtil.containsIgnoreCase(e.getMessage(), "404")
                    || StrUtil.containsIgnoreCase(e.getMessage(), "not found")) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Grav→OM：仅 upsert 结构（列名/类型），不覆盖业务注释 / owner / tags
     *
     * @param serviceName OM DatabaseService 名
     * @param database    OM Database 名
     * @param schema      OM Schema（库内 schema）名
     * @param grav        Grav 表结构
     * @return omTableId / fqn / created / skipped
     */
    public Map<String, Object> upsertTableStructure(String serviceName, String database, String schema,
                                                  GravitinoClient.GravTable grav) {
        String fqn = serviceName + "." + database + "." + schema + "." + grav.name;
        JSONObject existing = getTableByFqn(fqn);

        Map<String, String> existingColDesc = new LinkedHashMap<>();
        String tableDesc = null;
        Object owners = null;
        Object tags = null;
        if (existing != null) {
            tableDesc = existing.getStr("description");
            owners = existing.get("owners");
            tags = existing.get("tags");
            JSONArray cols = existing.getJSONArray("columns");
            if (cols != null) {
                for (int i = 0; i < cols.size(); i++) {
                    JSONObject c = cols.getJSONObject(i);
                    String name = c.getStr("name");
                    String desc = c.getStr("description");
                    if (StrUtil.isNotBlank(name) && StrUtil.isNotBlank(desc)) {
                        existingColDesc.put(name, desc);
                    }
                }
            }
        }

        JSONArray columns = new JSONArray();
        for (GravitinoClient.GravColumn gc : grav.columns) {
            JSONObject col = new JSONObject();
            col.set("name", gc.name);
            col.set("dataType", mapOmDataType(gc.type));
            col.set("dataTypeDisplay", gc.type);
            // 保留 OM 已有列注释；新建列可用 Grav comment（技术注释）仅当 OM 无
            if (existingColDesc.containsKey(gc.name)) {
                col.set("description", existingColDesc.get(gc.name));
            } else if (existing == null && StrUtil.isNotBlank(gc.comment)) {
                col.set("description", gc.comment);
            }
            columns.add(col);
        }

        if (existing == null) {
            ensureDatabaseHierarchy(serviceName, database, schema);
            JSONObject create = new JSONObject();
            create.set("name", grav.name);
            create.set("tableType", "Regular");
            create.set("columns", columns);
            JSONObject dbSchema = new JSONObject();
            dbSchema.set("name", schema);
            dbSchema.set("fullyQualifiedName", serviceName + "." + database + "." + schema);
            create.set("databaseSchema", dbSchema);
            if (StrUtil.isNotBlank(grav.location)) {
                // location 非业务注释，可写
                create.set("sourceUrl", grav.location);
            }
            String body = authPost("/api/v1/tables", create.toString());
            JSONObject created = JSONUtil.parseObj(body);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("created", true);
            r.put("fqn", created.getStr("fullyQualifiedName", fqn));
            r.put("omTableId", created.getStr("id"));
            r.put("omRevision", created.getStr("version"));
            return r;
        }

        // 已存在：PUT 全量实体但强制带回 description/owners/tags
        JSONObject put = new JSONObject();
        put.set("id", existing.getStr("id"));
        put.set("name", existing.getStr("name", grav.name));
        put.set("tableType", existing.getStr("tableType", "Regular"));
        put.set("columns", columns);
        if (StrUtil.isNotBlank(tableDesc)) {
            put.set("description", tableDesc);
        }
        if (owners != null) {
            put.set("owners", owners);
        }
        if (tags != null) {
            put.set("tags", tags);
        }
        // 保留 glossary / extension 等若存在
        if (existing.get("glossaryTerms") != null) {
            put.set("glossaryTerms", existing.get("glossaryTerms"));
        }
        if (existing.get("extension") != null) {
            put.set("extension", existing.get("extension"));
        }
        Object dbSchema = existing.get("databaseSchema");
        if (dbSchema != null) {
            put.set("databaseSchema", dbSchema);
        }
        String body = authPut("/api/v1/tables", put.toString());
        JSONObject updated = JSONUtil.parseObj(body);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("created", false);
        r.put("fqn", updated.getStr("fullyQualifiedName", fqn));
        r.put("omTableId", updated.getStr("id", existing.getStr("id")));
        r.put("omRevision", updated.getStr("version"));
        return r;
    }

    private void ensureDatabaseHierarchy(String service, String database, String schema) {
        // 尽力创建；已存在则忽略 4xx
        try {
            JSONObject s = new JSONObject();
            s.set("name", service);
            s.set("serviceType", "CustomDatabase");
            s.set("connection", new JSONObject().set("config", new JSONObject().set("type", "CustomDatabase")));
            authPost("/api/v1/services/databaseServices", s.toString());
        } catch (Exception ignored) {
            // exists
        }
        try {
            JSONObject db = new JSONObject();
            db.set("name", database);
            db.set("service", service);
            authPost("/api/v1/databases", db.toString());
        } catch (Exception ignored) {
            // exists
        }
        try {
            JSONObject sch = new JSONObject();
            sch.set("name", schema);
            sch.set("database", service + "." + database);
            authPost("/api/v1/databaseSchemas", sch.toString());
        } catch (Exception ignored) {
            // exists
        }
    }

    /**
     * 粗粒度类型映射到 OM ColumnDataType
     */
    private String mapOmDataType(String gravType) {
        if (StrUtil.isBlank(gravType)) {
            return "UNKNOWN";
        }
        String t = gravType.toLowerCase(Locale.ROOT);
        if (t.contains("varchar") || t.contains("string") || t.contains("char") || t.contains("text")) {
            return "VARCHAR";
        }
        if (t.contains("int") || t.contains("long") || t.contains("short") || t.equals("bigint")) {
            return "BIGINT";
        }
        if (t.contains("double") || t.contains("float") || t.contains("real")) {
            return "DOUBLE";
        }
        if (t.contains("decimal") || t.contains("numeric")) {
            return "DECIMAL";
        }
        if (t.contains("bool")) {
            return "BOOLEAN";
        }
        if (t.contains("timestamp")) {
            return "TIMESTAMP";
        }
        if (t.contains("date")) {
            return "DATE";
        }
        if (t.contains("binary") || t.contains("bytes")) {
            return "BYTES";
        }
        if (t.contains("array")) {
            return "ARRAY";
        }
        if (t.contains("map") || t.contains("struct")) {
            return "MAP";
        }
        return "VARCHAR";
    }

    private String authGet(String path) {
        String base = trim(lhProperties.getOpenmetadata().getUrl());
        String token = credentialResolver.openMetadataToken();
        var resp = HttpRequest.get(base + path)
                .header("Authorization", "Bearer " + token)
                .timeout(15000)
                .execute();
        if (resp.getStatus() >= 400) {
            throw new CommonException("OpenMetadata GET 失败 {}: {}", resp.getStatus(), resp.body());
        }
        return resp.body();
    }

    private String authPost(String path, String json) {
        String base = trim(lhProperties.getOpenmetadata().getUrl());
        String token = credentialResolver.openMetadataToken();
        var resp = HttpRequest.post(base + path)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .body(json)
                .timeout(20000)
                .execute();
        if (resp.getStatus() >= 400) {
            throw new CommonException("OpenMetadata POST 失败 {}: {}", resp.getStatus(), resp.body());
        }
        return resp.body();
    }

    private String authPut(String path, String json) {
        String base = trim(lhProperties.getOpenmetadata().getUrl());
        String token = credentialResolver.openMetadataToken();
        var resp = HttpRequest.put(base + path)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .body(json)
                .timeout(20000)
                .execute();
        if (resp.getStatus() >= 400) {
            throw new CommonException("OpenMetadata PUT 失败 {}: {}", resp.getStatus(), resp.body());
        }
        return resp.body();
    }

    private String encPath(String fqn) {
        return fqn == null ? "" : fqn.replace(" ", "%20");
    }

    private String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }
}
