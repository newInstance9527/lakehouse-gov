package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClickHouse HTTP 客户端（凭证来自 Vault）。
 * dry-run COUNT 用平台只读 SA；合规 mutation 用 {@code sa_compliance}。
 */
@Component
public class ClickHouseClient {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    public boolean configured() {
        return StrUtil.isNotBlank(baseUrl());
    }

    /**
     * 执行 SQL（默认追加 {@code FORMAT JSON}），平台 ClickHouse 凭证。
     * 返回结构对齐 TrinoClient：{@code rows}/{@code degraded}/{@code message}。
     */
    public Map<String, Object> query(String sql) {
        return query(sql, credentialResolver.clickhouse(), false);
    }

    /**
     * 合规 mutation / 校验：使用 {@code sa_compliance}（缺省回退平台 CK 凭证）。
     * {@code rawResult=true} 时不强制 FORMAT JSON（ALTER 无结果集）。
     */
    public Map<String, Object> queryAsComplianceSa(String sql, boolean rawResult) {
        return query(sql, credentialResolver.complianceSa(), rawResult);
    }

    public Map<String, Object> query(String sql, Map<String, String> cred, boolean rawResult) {
        Map<String, Object> out = new LinkedHashMap<>();
        String url = baseUrl();
        if (StrUtil.isBlank(url)) {
            out.put("columns", List.of());
            out.put("rows", List.of());
            out.put("rowCount", 0);
            out.put("degraded", true);
            out.put("message", "lh.clickhouse.url 未配置");
            return out;
        }
        String body = StrUtil.trim(sql);
        if (StrUtil.isBlank(body)) {
            out.put("columns", List.of());
            out.put("rows", List.of());
            out.put("rowCount", 0);
            out.put("degraded", true);
            out.put("message", "SQL 为空");
            return out;
        }
        if (!rawResult && !body.toUpperCase(java.util.Locale.ROOT).contains("FORMAT")) {
            body = body + " FORMAT JSON";
        }
        Map<String, String> c = cred == null ? Map.of() : cred;
        String user = StrUtil.blankToDefault(c.get("username"), c.get("user"));
        String password = StrUtil.blankToDefault(c.get("password"), "");
        try {
            // mutation 可能较久；mutations_sync=2 时阻塞至本副本完成
            int timeout = rawResult ? 300_000 : 60_000;
            HttpRequest req = HttpRequest.post(trimSlash(url) + "/")
                    .timeout(timeout)
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .body(body);
            if (StrUtil.isNotBlank(user)) {
                String token = Base64.getEncoder().encodeToString(
                        (user + ":" + password).getBytes(StandardCharsets.UTF_8));
                req.header("Authorization", "Basic " + token);
            }
            HttpResponse resp = req.execute();
            int code = resp.getStatus();
            String raw = resp.body();
            out.put("httpStatus", code);
            if (code < 200 || code >= 300) {
                out.put("columns", List.of());
                out.put("rows", List.of());
                out.put("rowCount", 0);
                out.put("degraded", true);
                out.put("message", "ClickHouse HTTP " + code + ": "
                        + StrUtil.maxLength(StrUtil.blankToDefault(raw, ""), 200));
                return out;
            }
            if (rawResult || StrUtil.isBlank(raw) || !StrUtil.trim(raw).startsWith("{")) {
                out.put("columns", List.of());
                out.put("rows", List.of());
                out.put("rowCount", 0);
                out.put("degraded", false);
                out.put("message", "ok");
                out.put("raw", StrUtil.maxLength(StrUtil.blankToDefault(raw, ""), 200));
                return out;
            }
            JSONObject json = JSONUtil.parseObj(raw);
            List<String> columns = new ArrayList<>();
            JSONArray meta = json.getJSONArray("meta");
            if (meta != null) {
                for (int i = 0; i < meta.size(); i++) {
                    JSONObject m = meta.getJSONObject(i);
                    if (m != null) {
                        columns.add(m.getStr("name"));
                    }
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            JSONArray data = json.getJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.size(); i++) {
                    JSONObject row = data.getJSONObject(i);
                    if (row == null) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (String key : row.keySet()) {
                        m.put(key, row.get(key));
                    }
                    rows.add(m);
                }
            }
            out.put("columns", columns);
            out.put("rows", rows);
            out.put("rowCount", rows.size());
            out.put("degraded", false);
            out.put("message", "ok");
            return out;
        } catch (Exception e) {
            out.put("columns", List.of());
            out.put("rows", List.of());
            out.put("rowCount", 0);
            out.put("degraded", true);
            out.put("message", "ClickHouse 暂不可达: "
                    + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return out;
        }
    }

    private String baseUrl() {
        LhProperties.Clickhouse c = lhProperties.getClickhouse();
        return c == null ? null : StrUtil.trimToNull(c.getUrl());
    }

    /** 可选 ON CLUSTER 名（yml {@code lh.clickhouse.cluster}）。 */
    public String cluster() {
        LhProperties.Clickhouse c = lhProperties.getClickhouse();
        return c == null ? null : StrUtil.trimToNull(c.getCluster());
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
