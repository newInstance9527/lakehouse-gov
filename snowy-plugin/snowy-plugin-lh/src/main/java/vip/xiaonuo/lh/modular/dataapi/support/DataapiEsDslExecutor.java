package vip.xiaonuo.lh.modular.dataapi.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.datasource.support.LhEsConnSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elasticsearch Query DSL 执行：门户直连 {@code POST /{index}/_search}，不经 SQLREST / EsDriver。
 * <p>正文为 JSON，须含 {@code index}（或 {@code _index}）；其余字段作 search body
 * （也可包在 {@code body}/{@code search} 下）。占位符 {@code #{name}} 按入参替换。
 */
@Component
public class DataapiEsDslExecutor {

    private static final Pattern PLACEHOLDER = Pattern.compile("#\\{([a-zA-Z_][\\w.]*)}");

    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhProperties lhProperties;

    public Map<String, Object> execute(String portalDsId, String script, List<Map<String, Object>> portalParams) {
        if (StrUtil.isBlank(portalDsId)) {
            throw new CommonException("ES_DSL 需要 portalDsId（Elasticsearch 数据源）");
        }
        if (StrUtil.isBlank(script)) {
            throw new CommonException("ES_DSL 需要 Query DSL JSON 正文");
        }
        LhDatasource ds = datasourceMapper.selectById(portalDsId);
        if (ds == null || !"NOT_DELETE".equals(ds.getDeleteFlag())) {
            throw new CommonException("数据源不存在: {}", portalDsId);
        }
        String type = StrUtil.blankToDefault(ds.getType(), "").trim().toLowerCase();
        if (!"elasticsearch".equals(type) && !"opensearch".equals(type) && !"es".equals(type)) {
            throw new CommonException("ES_DSL 仅支持 Elasticsearch 类数据源，当前 type={}", ds.getType());
        }

        Map<String, Object> secret = new LinkedHashMap<>(vaultClient.readOrEmpty(ds.getVaultPath()));
        if (StrUtil.isBlank(str(secret.get("host"))) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            secret.putIfAbsent("host", ds.getEndpointHost());
        }
        if (StrUtil.isBlank(str(secret.get("port"))) && StrUtil.isNotBlank(ds.getEndpointPort())) {
            secret.putIfAbsent("port", ds.getEndpointPort());
        }

        Map<String, Object> values = paramValueMap(portalParams);
        String rendered = renderPlaceholders(script.trim(), values);
        ParsedDsl parsed = parseDsl(rendered);

        boolean insecure = lhProperties.getHttp() != null && lhProperties.getHttp().isInsecureSsl();
        return LhEsConnSupport.withFirstHealthy(secret, insecure, base -> {
            String path = "/" + parsed.index() + "/_search";
            HttpResponse resp = LhEsConnSupport.post(base, path, parsed.bodyJson(), secret, insecure);
            return toTrialShape(resp.body(), parsed.index(), path);
        });
    }

    /** 从 DSL/SQL 文本提取 #{name} 入参名（门户本地解析，不经 SQLREST）。 */
    public static List<String> extractParamNames(String text) {
        LinkedHashMap<String, Boolean> names = new LinkedHashMap<>();
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            names.put(m.group(1), Boolean.TRUE);
        }
        return new ArrayList<>(names.keySet());
    }

    /** 构建期结构校验：合法 JSON + index + body，不访问 ES、不要求入参值。 */
    public static void validateStructure(String script) {
        if (StrUtil.isBlank(script)) {
            throw new CommonException("ES_DSL 正文不能为空");
        }
        parseDsl(script.trim());
    }

    private static Map<String, Object> toTrialShape(String rawBody, String index, String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("engine", "ES_DSL");
        out.put("index", index);
        out.put("searchPath", path);
        List<Map<String, Object>> sample = new ArrayList<>();
        List<Map<String, Object>> types = new ArrayList<>();
        try {
            JSONObject root = JSONUtil.parseObj(rawBody);
            out.put("took", root.get("took"));
            out.put("timedOut", root.get("timed_out"));
            JSONObject hitsWrap = root.getJSONObject("hits");
            if (hitsWrap != null) {
                out.put("total", hitsWrap.get("total"));
                JSONArray hits = hitsWrap.getJSONArray("hits");
                if (hits != null) {
                    for (int i = 0; i < hits.size(); i++) {
                        JSONObject h = hits.getJSONObject(i);
                        if (h == null) {
                            continue;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("_id", h.get("_id"));
                        row.put("_index", h.get("_index"));
                        row.put("_score", h.get("_score"));
                        Object src = h.get("_source");
                        if (src instanceof JSONObject jo) {
                            for (String k : jo.keySet()) {
                                row.put(k, jo.get(k));
                            }
                        } else if (src != null) {
                            row.put("_source", src);
                        }
                        sample.add(row);
                    }
                }
            }
            if (!sample.isEmpty()) {
                for (String k : sample.get(0).keySet()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", k);
                    t.put("type", "STRING");
                    types.add(t);
                }
            }
            out.put("raw", root);
        } catch (Exception e) {
            out.put("rawBody", StrUtil.maxLength(rawBody, 4000));
            out.put("parseWarning", e.getMessage());
        }
        out.put("sample", sample);
        out.put("types", types);
        out.put("sqlPreview", path);
        return out;
    }

    private static ParsedDsl parseDsl(String text) {
        if (!JSONUtil.isTypeJSON(text)) {
            throw new CommonException("ES_DSL 正文须为 JSON（含 index + query 等）");
        }
        JSONObject root = JSONUtil.parseObj(text);
        String index = StrUtil.blankToDefault(root.getStr("index"), root.getStr("_index"));
        if (StrUtil.isBlank(index)) {
            throw new CommonException("ES_DSL JSON 须包含 index（或 _index）字段，指明目标索引");
        }
        Object bodyNode = root.get("body");
        if (bodyNode == null) {
            bodyNode = root.get("search");
        }
        String bodyJson;
        if (bodyNode instanceof JSONObject || bodyNode instanceof JSONArray) {
            bodyJson = JSONUtil.toJsonStr(bodyNode);
        } else {
            JSONObject body = new JSONObject(root);
            body.remove("index");
            body.remove("_index");
            body.remove("body");
            body.remove("search");
            if (body.isEmpty()) {
                throw new CommonException("ES_DSL 缺少 search body（query / size 等）");
            }
            bodyJson = body.toString();
        }
        return new ParsedDsl(index.trim(), bodyJson);
    }

    private static String renderPlaceholders(String script, Map<String, Object> values) {
        Matcher m = PLACEHOLDER.matcher(script);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            if (!values.containsKey(name)) {
                throw new CommonException("缺少入参：{}", name);
            }
            Object v = values.get(name);
            String repl = jsonLiteral(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String jsonLiteral(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        // 已是 JSON 片段（数组/对象）则原样嵌入
        String s = String.valueOf(v);
        if (JSONUtil.isTypeJSON(s) && (s.startsWith("{") || s.startsWith("["))) {
            return s;
        }
        return JSONUtil.toJsonStr(s);
    }

    private static Map<String, Object> paramValueMap(List<Map<String, Object>> portalParams) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (portalParams == null) {
            return values;
        }
        for (Map<String, Object> p : portalParams) {
            if (p == null) {
                continue;
            }
            String name = str(p.get("name"));
            if (StrUtil.isBlank(name)) {
                continue;
            }
            Object val = p.containsKey("value") ? p.get("value") : p.get("example");
            values.put(name, val);
        }
        return values;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private record ParsedDsl(String index, String bodyJson) {
    }
}
