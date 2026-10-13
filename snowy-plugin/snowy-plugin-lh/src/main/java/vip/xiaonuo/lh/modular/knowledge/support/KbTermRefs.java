package vip.xiaonuo.lh.modular.knowledge.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 业务术语 refs_json 规范化与边抽取。
 * SoT：gov_kb_entry（cat=term）；OM Glossary 仅标准/指标 soft-fail 镜像，非业务术语写入口。
 */
public final class KbTermRefs {

    public static final String EDGE_SYNONYM = "synonym";
    public static final String EDGE_BROADER = "broader";
    public static final String EDGE_RELATED = "related";
    public static final String EDGE_METRIC = "implements_metric";
    public static final String EDGE_ASSET = "maps_column";
    public static final String EDGE_STD = "governed_by_std";

    public static final String KIND_TERM = "term";
    public static final String KIND_METRIC = "metric";
    public static final String KIND_ASSET = "asset";
    public static final String KIND_STD = "standard";
    public static final String KIND_LABEL = "label";

    private KbTermRefs() {
    }

    /**
     * 规范化 refs；非 JSON 的纯文案落到 note/label。
     * term 类会补 link（有 metricCode 时）。
     */
    public static String normalizeJson(String refsJson, String refsPlain, String cat) {
        JSONObject obj = parseObject(refsJson);
        if (obj == null && StrUtil.isNotBlank(refsPlain)) {
            if (JSONUtil.isTypeJSON(refsPlain)) {
                obj = parseObject(refsPlain);
            } else {
                obj = new JSONObject();
                obj.set("note", refsPlain.trim());
                obj.set("label", refsPlain.trim());
            }
        }
        if (obj == null) {
            return refsJson;
        }
        normalizeArrays(obj, "synonyms");
        normalizeArrays(obj, "standardIds");
        normalizeArrays(obj, "assets");
        normalizeArrays(obj, "broader");
        normalizeArrays(obj, "related");
        String metric = firstNonBlank(obj.getStr("metricCode"), obj.getStr("metric"));
        if (StrUtil.isNotBlank(metric)) {
            obj.set("metricCode", metric.trim());
            obj.remove("metric");
        }
        if ("term".equalsIgnoreCase(StrUtil.nullToEmpty(cat))
                && StrUtil.isNotBlank(obj.getStr("metricCode"))
                && StrUtil.isBlank(obj.getStr("link"))) {
            obj.set("link", "/metric?code=" + obj.getStr("metricCode").trim());
        }
        return obj.toString();
    }

    public static List<EdgeDraft> extractEdges(String entryId, String refsJson, String ws) {
        List<EdgeDraft> out = new ArrayList<>();
        if (StrUtil.isBlank(entryId) || StrUtil.isBlank(refsJson)) {
            return out;
        }
        JSONObject obj = parseObject(refsJson);
        if (obj == null) {
            return out;
        }
        String metric = obj.getStr("metricCode");
        if (StrUtil.isNotBlank(metric)) {
            out.add(new EdgeDraft(entryId, EDGE_METRIC, KIND_METRIC, metric.trim(), metric.trim(), ws));
        }
        for (String s : stringList(obj, "standardIds")) {
            out.add(new EdgeDraft(entryId, EDGE_STD, KIND_STD, s, s, ws));
        }
        for (String a : stringList(obj, "assets")) {
            out.add(new EdgeDraft(entryId, EDGE_ASSET, KIND_ASSET, a, a, ws));
        }
        for (String syn : stringList(obj, "synonyms")) {
            out.add(new EdgeDraft(entryId, EDGE_SYNONYM, KIND_LABEL, syn, syn, ws));
        }
        for (String b : stringList(obj, "broader")) {
            out.add(new EdgeDraft(entryId, EDGE_BROADER, KIND_TERM, b, b, ws));
        }
        for (String r : stringList(obj, "related")) {
            out.add(new EdgeDraft(entryId, EDGE_RELATED, KIND_TERM, r, r, ws));
        }
        return out;
    }

    public static Map<String, Object> toMap(String refsJson) {
        JSONObject obj = parseObject(refsJson);
        if (obj == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (String key : obj.keySet()) {
            m.put(key, obj.get(key));
        }
        return m;
    }

    private static void normalizeArrays(JSONObject obj, String key) {
        List<String> list = stringList(obj, key);
        if (list.isEmpty()) {
            Object raw = obj.get(key);
            if (raw instanceof CharSequence && StrUtil.isNotBlank(raw.toString())) {
                list = splitCsv(raw.toString());
            }
        }
        if (!list.isEmpty()) {
            obj.set(key, list);
        }
    }

    private static List<String> stringList(JSONObject obj, String key) {
        Set<String> set = new LinkedHashSet<>();
        Object raw = obj.get(key);
        if (raw instanceof JSONArray arr) {
            for (Object o : arr) {
                if (o != null && StrUtil.isNotBlank(o.toString())) {
                    set.add(o.toString().trim());
                }
            }
        } else if (raw instanceof CharSequence && StrUtil.isNotBlank(raw.toString())) {
            set.addAll(splitCsv(raw.toString()));
        }
        return new ArrayList<>(set);
    }

    private static List<String> splitCsv(String raw) {
        List<String> out = new ArrayList<>();
        for (String p : raw.split("[,，;；\\n]+")) {
            if (StrUtil.isNotBlank(p)) {
                out.add(p.trim());
            }
        }
        return out;
    }

    private static JSONObject parseObject(String json) {
        if (StrUtil.isBlank(json) || !JSONUtil.isTypeJSON(json)) {
            return null;
        }
        try {
            Object parsed = JSONUtil.parse(json);
            if (parsed instanceof JSONObject o) {
                return o;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (StrUtil.isNotBlank(a)) {
            return a;
        }
        return b;
    }

    public record EdgeDraft(
            String fromEntryId,
            String edgeType,
            String toKind,
            String toId,
            String toLabel,
            String ws) {
        public String dedupeKey() {
            return (edgeType + "|" + toKind + "|" + toId).toLowerCase(Locale.ROOT);
        }
    }
}
