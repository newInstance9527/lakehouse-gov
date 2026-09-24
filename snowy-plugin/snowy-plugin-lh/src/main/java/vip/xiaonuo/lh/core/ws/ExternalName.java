package vip.xiaonuo.lh.core.ws;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 第三方投影统一外部名：门户 {@code (ws, code)} → 外部可见名 / 绑定表 {@code ext_id}。
 * <p>规则：可见名 {@code {ws}__{code}}；绑定 {@code ext_id = {kind}:{可见名}}，跨消费方全局唯一。</p>
 */
public final class ExternalName {

    public static final String KIND_SQLREST_DS = "sqlrest_ds";
    public static final String KIND_SQLREST_API = "sqlrest_api";
    public static final String KIND_APISIX = "apisix";
    public static final String KIND_MARQUEZ_NS = "marquez_ns";
    public static final String KIND_MARQUEZ_JOB = "marquez_job";

    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9._-]+");
    private static final int MAX_SEGMENT = 64;
    private static final int MAX_OF = 128;
    private static final int MAX_EXT = 192;

    private ExternalName() {
    }

    /**
     * 第三方可见名：{@code {ws}__{code}}（非法字符压成 {@code _}，段长截断）。
     */
    public static String of(String ws, String code) {
        String w = sanitize(StrUtil.blankToDefault(ws, "default"));
        String c = sanitize(StrUtil.blankToDefault(code, "unnamed"));
        if (StrUtil.isBlank(w)) {
            w = "default";
        }
        if (StrUtil.isBlank(c)) {
            c = "unnamed";
        }
        String joined = w + "__" + c;
        return trimTo(joined, MAX_OF);
    }

    /**
     * 绑定表 {@code ext_id}：{@code {kind}:{of(ws,code)}}，保证跨 SQLREST/APISIX/Marquez 全局唯一。
     */
    public static String extId(String kind, String ws, String code) {
        String k = StrUtil.blankToDefault(kind, "ext").trim().toLowerCase(Locale.ROOT);
        k = UNSAFE.matcher(k).replaceAll("_");
        if (StrUtil.isBlank(k)) {
            k = "ext";
        }
        return trimTo(k + ":" + of(ws, code), MAX_EXT);
    }

    /** Marquez / OpenLineage namespace（按空间隔离） */
    public static String marquezNamespace(String ws) {
        return of(ws, "lakehouse");
    }

    /** Marquez job name：{@code {dagCode}.{nodeKey}}，落在 {@link #marquezNamespace(String)} 下 */
    public static String marquezJob(String dagCode, String nodeKey) {
        String dag = sanitize(StrUtil.blankToDefault(dagCode, "dag"));
        String node = sanitize(StrUtil.blankToDefault(nodeKey, "node"));
        return trimTo(dag + "." + node, MAX_OF);
    }

    static String sanitize(String raw) {
        String s = StrUtil.trim(raw);
        if (s == null) {
            return "";
        }
        s = UNSAFE.matcher(s).replaceAll("_");
        s = s.replaceAll("^_+|_+$", "");
        if (s.length() > MAX_SEGMENT) {
            s = s.substring(0, MAX_SEGMENT);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static String trimTo(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
