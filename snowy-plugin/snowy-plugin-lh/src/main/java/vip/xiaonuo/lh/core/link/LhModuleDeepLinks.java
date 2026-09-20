package vip.xiaonuo.lh.core.link;

import cn.hutool.core.util.StrUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨模块深链 path 契约（纯函数，无 Spring）。
 * <p>与前端 {@code utils/moduleLinks.js} 保持同源字段名。</p>
 *
 * @author lakehouse
 * @date 2026/9/19
 */
public final class LhModuleDeepLinks {

    private LhModuleDeepLinks() {
    }

    /** 打开资产详情；兼容接收端将 id 视为 asset */
    public static String catalogAsset(String assetIdOrCode) {
        if (StrUtil.isBlank(assetIdOrCode)) {
            return "/catalog";
        }
        return "/catalog?asset=" + enc(assetIdOrCode.trim());
    }

    /** 目录列表筛选 */
    public static String catalogSearch(String q) {
        if (StrUtil.isBlank(q)) {
            return "/catalog";
        }
        return "/catalog?q=" + enc(q.trim());
    }

    /**
     * 血缘页 focus；可选 omFqn / field / mode
     */
    public static String lineage(String focus, String omFqn, String field, String mode) {
        Map<String, String> q = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(focus)) {
            q.put("focus", focus.trim());
        }
        if (StrUtil.isNotBlank(omFqn)) {
            q.put("omFqn", omFqn.trim());
        }
        if (StrUtil.isNotBlank(mode)) {
            q.put("mode", mode.trim());
        }
        if (StrUtil.isNotBlank(field)) {
            q.put("field", field.trim());
        }
        return "/lineage" + query(q);
    }

    public static String lineageFocus(String focus) {
        return lineage(focus, null, null, null);
    }

    public static String lineageField(String focus, String field) {
        return lineage(focus, null, field, "field");
    }

    /** 质量页预填表关键字 */
    public static String quality(String tableQ) {
        if (StrUtil.isBlank(tableQ)) {
            return "/quality";
        }
        return "/quality?q=" + enc(tableQ.trim());
    }

    /** 标准映射 tab + 关键字 */
    public static String standardMapping(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tab", "mapping");
        if (StrUtil.isNotBlank(q)) {
            m.put("q", q.trim());
        }
        return "/standard" + query(m);
    }

    private static String query(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (StrUtil.isBlank(e.getValue())) {
                continue;
            }
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return first ? "" : sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
