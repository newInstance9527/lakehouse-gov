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

import java.util.*;
import java.util.Locale;

/**
 * Apache Gravitino REST 客户端（结构 SoT 读取侧）
 * <p>凭证来自 Vault Basic Auth；Accept: application/vnd.gravitino.v1+json。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class GravitinoClient {

    private static final String ACCEPT = "application/vnd.gravitino.v1+json";

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    /**
     * 健康探测
     */
    public Map<String, Object> health() {
        try {
            String body = authGet("/api/version");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", "gravitino");
            m.put("status", "UP");
            m.put("body", body);
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", "gravitino");
            m.put("status", "DOWN");
            m.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return m;
        }
    }

    /**
     * 列出 metalake 下 catalog 名
     */
    public List<String> listCatalogs(String metalake) {
        String path = "/api/metalakes/" + enc(metalake) + "/catalogs";
        JSONObject root = JSONUtil.parseObj(authGet(path));
        return extractNames(root);
    }

    /**
     * 列出 catalog 下 schema 名
     */
    public List<String> listSchemas(String metalake, String catalog) {
        String path = "/api/metalakes/" + enc(metalake) + "/catalogs/" + enc(catalog) + "/schemas";
        JSONObject root = JSONUtil.parseObj(authGet(path));
        return extractNames(root);
    }

    /**
     * 列出 schema 下表名
     */
    public List<String> listTables(String metalake, String catalog, String schema) {
        String path = "/api/metalakes/" + enc(metalake) + "/catalogs/" + enc(catalog)
                + "/schemas/" + enc(schema) + "/tables";
        JSONObject root = JSONUtil.parseObj(authGet(path));
        return extractNames(root);
    }

    /**
     * 加载表结构（列名/类型/分区）
     */
    public GravTable loadTable(String metalake, String catalog, String schema, String table) {
        String path = "/api/metalakes/" + enc(metalake) + "/catalogs/" + enc(catalog)
                + "/schemas/" + enc(schema) + "/tables/" + enc(table);
        JSONObject root = JSONUtil.parseObj(authGet(path));
        JSONObject t = root.getJSONObject("table");
        if (t == null) {
            t = root;
        }
        GravTable gt = new GravTable();
        gt.metalake = metalake;
        gt.catalog = catalog;
        gt.schema = schema;
        gt.name = StrUtil.blankToDefault(t.getStr("name"), table);
        gt.comment = t.getStr("comment");
        gt.auditVersion = extractAuditVersion(t);
        gt.location = firstStr(t, "storageLocation", "location", "properties.location");
        JSONArray cols = t.getJSONArray("columns");
        if (cols != null) {
            for (int i = 0; i < cols.size(); i++) {
                JSONObject c = cols.getJSONObject(i);
                GravColumn col = new GravColumn();
                col.name = c.getStr("name");
                col.type = stringifyType(c.get("type"));
                col.nullable = !Boolean.FALSE.equals(c.getBool("nullable"));
                col.comment = c.getStr("comment");
                col.properties = extractColumnProperties(c);
                col.masked = detectColumnMasked(c, col.properties);
                gt.columns.add(col);
            }
        }
        JSONArray pk = t.getJSONArray("partitioning");
        if (pk == null) {
            pk = t.getJSONArray("partitions");
        }
        if (pk != null) {
            for (int i = 0; i < pk.size(); i++) {
                Object p = pk.get(i);
                if (p instanceof JSONObject jo) {
                    String field = firstStr(jo, "fieldName", "field", "name");
                    if (StrUtil.isNotBlank(field)) {
                        gt.partitionKeys.add(field);
                    }
                } else if (p != null) {
                    gt.partitionKeys.add(String.valueOf(p));
                }
            }
        }
        return gt;
    }

    /**
     * 确保 schema 存在（不存在则创建，soft 路径由调用方捕获）
     */
    public void ensureSchema(String metalake, String catalog, String schema) {
        ensureSchema(metalake, catalog, schema, "created by lakehouse etl autoCreate");
    }

    public void ensureSchema(String metalake, String catalog, String schema, String comment) {
        String path = "/api/metalakes/" + enc(metalake) + "/catalogs/" + enc(catalog)
                + "/schemas/" + enc(schema);
        try {
            authGet(path);
        } catch (Exception e) {
            JSONObject body = new JSONObject();
            body.set("name", schema);
            body.set("comment", StrUtil.blankToDefault(comment, "created by lakehouse"));
            authPost("/api/metalakes/" + enc(metalake) + "/catalogs/" + enc(catalog) + "/schemas",
                    body.toString());
        }
    }

    /**
     * 创建表（Iceberg / Lakehouse catalog）；列 type 用简单字符串。
     */
    public void createTable(String metalake, String catalog, String schema, String table,
                            List<Map<String, Object>> columns, String comment) {
        ensureSchema(metalake, catalog, schema);
        JSONObject body = new JSONObject();
        body.set("name", table);
        if (StrUtil.isNotBlank(comment)) {
            body.set("comment", comment);
        }
        JSONArray cols = new JSONArray();
        if (columns != null) {
            for (Map<String, Object> c : columns) {
                if (c == null || c.get("name") == null) {
                    continue;
                }
                JSONObject col = new JSONObject();
                col.set("name", String.valueOf(c.get("name")));
                col.set("type", StrUtil.blankToDefault(String.valueOf(c.get("type")), "string"));
                Object nullable = c.get("nullable");
                col.set("nullable", nullable == null || Boolean.TRUE.equals(nullable)
                        || "true".equalsIgnoreCase(String.valueOf(nullable)));
                if (c.get("comment") != null) {
                    col.set("comment", String.valueOf(c.get("comment")));
                }
                cols.add(col);
            }
        }
        body.set("columns", cols);
        String path = "/api/metalakes/" + enc(metalake) + "/catalogs/" + enc(catalog)
                + "/schemas/" + enc(schema) + "/tables";
        authPost(path, body.toString());
    }

    /**
     * 向 Gravitino 授予表权限。失败由调用方决定是否中断；本方法不写门户 ACL。
     */
    public Map<String, Object> grantTablePrivilege(String metalake, String catalog, String schema, String table,
                                                   String subjectId, String privilege) {
        return grantTablePrivilege(metalake, catalog, schema, table, subjectId, privilege, null);
    }

    public Map<String, Object> grantTablePrivilege(String metalake, String catalog, String schema, String table,
                                                   String subjectId, String privilege, String rowFilter) {
        return mutateTablePrivilege(true, metalake, catalog, schema, table, subjectId, privilege, rowFilter);
    }

    /** 回收 Gravitino 表权限。 */
    public Map<String, Object> revokeTablePrivilege(String metalake, String catalog, String schema, String table,
                                                    String subjectId, String privilege) {
        return mutateTablePrivilege(false, metalake, catalog, schema, table, subjectId, privilege, null);
    }

    /**
     * 出库审计：给表打 lh.export.* 属性（soft-fail 由调用方处理）。
     * Grav TableChange setProperty；无表坐标时返回 ok=false。
     */
    public Map<String, Object> setTableProperties(String metalake, String catalog, String schema, String table,
                                                  Map<String, String> properties) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (StrUtil.hasBlank(metalake, catalog, schema, table) || properties == null || properties.isEmpty()) {
            out.put("message", "metalake/catalog/schema/table/properties 不能为空");
            return out;
        }
        String fullName = catalog + "." + schema + "." + table;
        out.put("fullName", metalake + "." + fullName);
        try {
            JSONArray updates = new JSONArray();
            properties.forEach((k, v) -> {
                if (StrUtil.isBlank(k)) {
                    return;
                }
                JSONObject sp = new JSONObject();
                sp.set("@type", "setProperty");
                sp.set("property", k);
                sp.set("value", v == null ? "" : v);
                updates.add(sp);
            });
            if (updates.isEmpty()) {
                out.put("message", "无有效属性");
                return out;
            }
            JSONObject put = new JSONObject();
            put.set("updates", updates);
            String path = "/api/metalakes/" + enc(metalake)
                    + "/catalogs/" + enc(catalog)
                    + "/schemas/" + enc(schema)
                    + "/tables/" + enc(table);
            String resp = authPut(path, put.toString());
            out.put("ok", true);
            out.put("response", resp);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
        }
        return out;
    }

    /**
     * Gravitino ≥1.0 ACL：用户 ← Role ← Privilege（SELECT_TABLE 等）。
     * 旧路径 {@code /permissions/user/...} 已不存在（会 404）。
     */
    private Map<String, Object> mutateTablePrivilege(boolean grant, String metalake, String catalog, String schema,
                                                     String table, String subjectId, String privilege, String rowFilter) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (StrUtil.hasBlank(metalake, catalog, schema, table, subjectId)) {
            out.put("message", "metalake/catalog/schema/table/subject 不能为空");
            return out;
        }
        String gravPriv = toGravPrivilege(privilege);
        String fullName = catalog + "." + schema + "." + table;
        String role = portalSelectRole(subjectId);
        out.put("role", role);
        out.put("privilege", gravPriv);
        out.put("fullName", metalake + "." + fullName);
        // rowFilter 不是 Grav privilege.condition（仅为 ALLOW/DENY）；行过滤仍落门户投影
        if (StrUtil.isNotBlank(rowFilter)) {
            out.put("rowFilterNote", "rowFilter 仅写门户投影，未写入 Grav privilege.condition");
        }
        try {
            ensureUser(metalake, subjectId);
            ensureRole(metalake, role);
            String objectPath = "/api/metalakes/" + enc(metalake)
                    + "/permissions/roles/" + enc(role)
                    + "/table/" + enc(fullName)
                    + (grant ? "/grant" : "/revoke");
            JSONObject body = new JSONObject();
            JSONArray privileges = new JSONArray();
            JSONObject one = new JSONObject();
            one.set("name", gravPriv);
            one.set("condition", "ALLOW");
            privileges.add(one);
            body.set("privileges", privileges);
            String resp = authPut(objectPath, body.toString());
            if (grant) {
                JSONObject grantUser = new JSONObject();
                grantUser.set("roleNames", List.of(role));
                authPut("/api/metalakes/" + enc(metalake) + "/permissions/users/" + enc(subjectId) + "/grant",
                        grantUser.toString());
            }
            out.put("ok", true);
            out.put("policyId", "grav-role:" + role + ":" + fullName + ":" + gravPriv);
            out.put("response", resp);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
        }
        return out;
    }

    /** 门户人查主体对应的 Grav Role（每用户一个，多表复用）。 */
    static String portalSelectRole(String trinoUser) {
        String raw = StrUtil.blankToDefault(trinoUser, "anonymous").trim();
        String safe = raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (safe.length() > 48) {
            safe = safe.substring(0, 48);
        }
        return "lh_sel_" + safe;
    }

    static String toGravPrivilege(String privilege) {
        String p = StrUtil.blankToDefault(privilege, "SELECT").trim().toUpperCase(Locale.ROOT);
        return switch (p) {
            case "SELECT", "SELECT_TABLE", "READ" -> "SELECT_TABLE";
            case "INSERT", "UPDATE", "DELETE", "MODIFY", "MODIFY_TABLE", "WRITE" -> "MODIFY_TABLE";
            default -> p.contains("SELECT") ? "SELECT_TABLE" : p;
        };
    }

    private void ensureUser(String metalake, String user) {
        String getPath = "/api/metalakes/" + enc(metalake) + "/users/" + enc(user);
        try {
            authGet(getPath);
            return;
        } catch (Exception ignored) {
            // 404 → create
        }
        JSONObject body = new JSONObject();
        body.set("name", user);
        try {
            authPost("/api/metalakes/" + enc(metalake) + "/users", body.toString());
        } catch (Exception e) {
            // 并发创建：再 GET 一次确认
            try {
                authGet(getPath);
            } catch (Exception e2) {
                throw e;
            }
        }
    }

    private void ensureRole(String metalake, String role) {
        String getPath = "/api/metalakes/" + enc(metalake) + "/roles/" + enc(role);
        try {
            authGet(getPath);
            return;
        } catch (Exception ignored) {
            // 404 → create
        }
        JSONObject body = new JSONObject();
        body.set("name", role);
        body.set("properties", Map.of("managedBy", "lakehouse-portal"));
        body.set("securableObjects", new JSONArray());
        try {
            authPost("/api/metalakes/" + enc(metalake) + "/roles", body.toString());
        } catch (Exception e) {
            try {
                authGet(getPath);
            } catch (Exception e2) {
                throw e;
            }
        }
    }

    private long extractAuditVersion(JSONObject t) {
        JSONObject audit = t.getJSONObject("audit");
        if (audit != null) {
            Long v = audit.getLong("version");
            if (v != null) {
                return v;
            }
            // 用更新时间毫秒作伪 revision
            String ts = firstStr(audit, "lastModifiedTime", "createTime");
            if (StrUtil.isNotBlank(ts)) {
                try {
                    return Long.parseLong(ts.replaceAll("\\D", "").substring(0, Math.min(13, ts.replaceAll("\\D", "").length())));
                } catch (Exception ignored) {
                    // fallthrough
                }
            }
        }
        // 结构 hash 兜底：列签名
        return Math.abs(Objects.hash(t.toString()));
    }

    private List<String> extractNames(JSONObject root) {
        List<String> names = new ArrayList<>();
        // identifiers: ["a","b"] 或 [{name:..}] / names
        Object idents = root.get("identifiers");
        if (idents == null) {
            idents = root.get("names");
        }
        if (idents instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                Object item = arr.get(i);
                if (item instanceof String s) {
                    names.add(lastSegment(s));
                } else if (item instanceof JSONObject jo) {
                    String n = firstStr(jo, "name", "table", "schema");
                    if (StrUtil.isNotBlank(n)) {
                        names.add(lastSegment(n));
                    }
                }
            }
        }
        return names;
    }

    private String stringifyType(Object type) {
        if (type == null) {
            return "UNKNOWN";
        }
        if (type instanceof String s) {
            return s;
        }
        if (type instanceof JSONObject jo) {
            String t = jo.getStr("type");
            if (StrUtil.isNotBlank(t)) {
                return t;
            }
        }
        return String.valueOf(type);
    }

    private static Map<String, String> extractColumnProperties(JSONObject c) {
        Map<String, String> props = new LinkedHashMap<>();
        if (c == null) {
            return props;
        }
        Object raw = c.get("properties");
        if (raw instanceof JSONObject jo) {
            for (String key : jo.keySet()) {
                Object v = jo.get(key);
                if (v != null) {
                    props.put(key, String.valueOf(v));
                }
            }
        }
        return props;
    }

    /**
     * Grav 列是否挂了脱敏/敏感策略（properties 或显式字段；非列名启发式）。
     */
    public static boolean detectColumnMasked(JSONObject c, Map<String, String> properties) {
        if (c != null) {
            if (Boolean.TRUE.equals(c.getBool("masked")) || Boolean.TRUE.equals(c.getBool("mask"))) {
                return true;
            }
            String maskExpr = firstStr(c, "mask", "maskExpression", "columnMask");
            if (StrUtil.isNotBlank(maskExpr) && !"false".equalsIgnoreCase(maskExpr) && !"none".equalsIgnoreCase(maskExpr)) {
                return true;
            }
        }
        if (properties == null || properties.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> e : properties.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (k.contains("mask") || k.contains("pii") || k.contains("sensitive")
                    || "lh.mask".equals(k) || "security.mask".equals(k)) {
                if (StrUtil.isBlank(v) || "false".equalsIgnoreCase(v) || "0".equals(v)
                        || "none".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private String authGet(String path) {
        return authRequest("GET", path, null);
    }

    private String authPost(String path, String json) {
        return authRequest("POST", path, json);
    }

    private String authPut(String path, String json) {
        return authRequest("PUT", path, json);
    }

    private String authDelete(String path, String json) {
        return authRequest("DELETE", path, json);
    }

    private String authRequest(String method, String path, String json) {
        String base = trim(lhProperties.getGravitino().getUrl());
        Map<String, String> cred = credentialResolver.gravitino();
        String user = cred.get("username");
        String pass = cred.get("password");
        HttpRequest req;
        if ("POST".equalsIgnoreCase(method)) {
            req = HttpRequest.post(base + path).body(json);
        } else if ("PUT".equalsIgnoreCase(method)) {
            req = HttpRequest.put(base + path).body(json);
        } else if ("DELETE".equalsIgnoreCase(method)) {
            req = HttpRequest.delete(base + path).body(json);
        } else {
            req = HttpRequest.get(base + path);
        }
        var resp = req.header("Accept", ACCEPT)
                .header("Content-Type", "application/json")
                .basicAuth(StrUtil.blankToDefault(user, "admin"), StrUtil.nullToEmpty(pass))
                .timeout(20000)
                .execute();
        if (resp.getStatus() >= 400) {
            throw new CommonException("Gravitino 请求失败 {}: {}", resp.getStatus(), resp.body());
        }
        return resp.body();
    }

    /**
     * 创建或更新 Catalog（登记数据源投影，无需审批）
     *
     * @return created=true/false
     */
    public Map<String, Object> upsertCatalog(String metalake, String catalogName, String type,
                                             String provider, String comment, Map<String, String> properties) {
        ensureMetalake(metalake);
        JSONObject body = new JSONObject();
        body.set("name", catalogName);
        body.set("type", type);
        body.set("provider", provider);
        if (StrUtil.isNotBlank(comment)) {
            body.set("comment", comment);
        }
        body.set("properties", properties == null ? Map.of() : properties);

        String listPath = "/api/metalakes/" + enc(metalake) + "/catalogs/" + enc(catalogName);
        boolean exists = false;
        try {
            authGet(listPath);
            exists = true;
        } catch (Exception ignored) {
            // 404 → create
        }
        if (exists) {
            // Grav CatalogChange：setProperty / updateComment
            JSONArray updates = new JSONArray();
            if (StrUtil.isNotBlank(comment)) {
                JSONObject uc = new JSONObject();
                uc.set("@type", "updateComment");
                uc.set("newComment", comment);
                updates.add(uc);
            }
            if (properties != null) {
                properties.forEach((k, v) -> {
                    JSONObject sp = new JSONObject();
                    sp.set("@type", "setProperty");
                    sp.set("property", k);
                    sp.set("value", v == null ? "" : v);
                    updates.add(sp);
                });
            }
            JSONObject put = new JSONObject();
            put.set("updates", updates);
            authPut("/api/metalakes/" + enc(metalake) + "/catalogs/" + enc(catalogName), put.toString());
            return Map.of("created", false, "catalog", catalogName, "metalake", metalake);
        }
        authPost("/api/metalakes/" + enc(metalake) + "/catalogs", body.toString());
        return Map.of("created", true, "catalog", catalogName, "metalake", metalake);
    }

    private void ensureMetalake(String metalake) {
        try {
            authGet("/api/metalakes/" + enc(metalake));
        } catch (Exception e) {
            JSONObject create = new JSONObject();
            create.set("name", metalake);
            create.set("comment", "lakehouse metalake");
            try {
                authPost("/api/metalakes", create.toString());
            } catch (Exception ignored) {
                // 并发创建
            }
        }
    }

    private static String lastSegment(String s) {
        if (s == null) {
            return "";
        }
        int idx = Math.max(s.lastIndexOf('.'), s.lastIndexOf('/'));
        return idx >= 0 ? s.substring(idx + 1) : s;
    }

    private static String firstStr(JSONObject o, String... keys) {
        for (String k : keys) {
            if (k.contains(".")) {
                Object v = o.getByPath(k);
                if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                    return String.valueOf(v);
                }
            } else {
                String v = o.getStr(k);
                if (StrUtil.isNotBlank(v)) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String enc(String s) {
        return s == null ? "" : s.replace("/", "%2F");
    }

    private static String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }

    /** Grav 表结构快照 */
    public static class GravTable {
        public String metalake;
        public String catalog;
        public String schema;
        public String name;
        public String comment;
        public String location;
        public long auditVersion;
        public List<GravColumn> columns = new ArrayList<>();
        public List<String> partitionKeys = new ArrayList<>();
    }

    /** Grav 列 */
    public static class GravColumn {
        public String name;
        public String type;
        public boolean nullable = true;
        public String comment;
        /** 引擎/Catalog 带回的列属性（含 mask 策略键） */
        public Map<String, String> properties = new LinkedHashMap<>();
        /** true：Grav/策略声明该列需脱敏（非列名启发式） */
        public boolean masked;
    }
}
