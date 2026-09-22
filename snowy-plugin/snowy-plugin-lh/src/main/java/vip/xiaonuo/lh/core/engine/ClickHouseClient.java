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
 * ClickHouse HTTP 只读客户端（凭证来自 Vault）。用于合规 dry-run COUNT 等轻量查询。
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
     * 执行单条只读 SQL，期望 {@code FORMAT JSON} 结果。
     * 返回结构对齐 TrinoClient：{@code rows}/{@code degraded}/{@code message}。
     */
    public Map<String, Object> query(String sql) {
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
        if (!body.toUpperCase(java.util.Locale.ROOT).contains("FORMAT")) {
            body = body + " FORMAT JSON";
        }
        Map<String, String> cred = credentialResolver.clickhouse();
        String user = StrUtil.blankToDefault(cred.get("username"), cred.get("user"));
        String password = StrUtil.blankToDefault(cred.get("password"), "");
        try {
            HttpRequest req = HttpRequest.post(trimSlash(url) + "/")
                    .timeout(60_000)
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
