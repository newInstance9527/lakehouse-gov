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
            return Map.of("component", "gravitino", "status", "UP", "body", body);
        } catch (Exception e) {
            return Map.of("component", "gravitino", "status", "DOWN", "error", e.getMessage());
        }
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

    private String authGet(String path) {
        String base = trim(lhProperties.getGravitino().getUrl());
        Map<String, String> cred = credentialResolver.gravitino();
        String user = cred.get("username");
        String pass = cred.get("password");
        var resp = HttpRequest.get(base + path)
                .header("Accept", ACCEPT)
                .header("Content-Type", "application/json")
                .basicAuth(StrUtil.blankToDefault(user, "admin"), StrUtil.nullToEmpty(pass))
                .timeout(15000)
                .execute();
        if (resp.getStatus() >= 400) {
            throw new CommonException("Gravitino 请求失败 {}: {}", resp.getStatus(), resp.body());
        }
        return resp.body();
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
    }
}
