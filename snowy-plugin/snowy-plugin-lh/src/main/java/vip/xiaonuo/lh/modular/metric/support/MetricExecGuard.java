package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 指标执行门禁（相对即席略宽：允许系统生成的聚合；仍禁写、要求有过滤或 LIMIT）。
 */
public final class MetricExecGuard {

    private static final Pattern WRITE = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|truncate|alter|create)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_LIT = Pattern.compile("DATE\\s+'", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT = Pattern.compile("\\blimit\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);

    private MetricExecGuard() {
    }

    /** @return null 通过；否则阻断原因 */
    public static String blockReason(String sql) {
        if (StrUtil.isBlank(sql)) {
            return "SQL 为空";
        }
        if (WRITE.matcher(sql).find()) {
            return "指标查询仅允许只读 SELECT";
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        boolean hasWhere = WHERE.matcher(lower).find();
        boolean hasDate = DATE_LIT.matcher(sql).find();
        boolean hasLimit = LIMIT.matcher(lower).find();
        // 有 WHERE（含时间/业务限定）或 LIMIT 即可
        if (!hasWhere && !hasLimit && !hasDate) {
            return "缺少时间/过滤条件且无 LIMIT，拒绝提交引擎（请配置统计周期或缩小范围）";
        }
        return null;
    }

    public static Map<String, Object> blockedPayload(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blocked", true);
        m.put("executed", false);
        m.put("status", "blocked");
        m.put("statusLabel", "⚠ " + reason);
        m.put("message", reason);
        m.put("rows", java.util.List.of());
        m.put("columns", java.util.List.of());
        m.put("rowCount", 0);
        return m;
    }

    /** 纯原子无 WHERE 时包一层 LIMIT */
    public static String ensureLimit(String sql, int maxRows) {
        if (StrUtil.isBlank(sql)) {
            return sql;
        }
        if (LIMIT.matcher(sql).find()) {
            return sql;
        }
        if (WHERE.matcher(sql.toLowerCase(Locale.ROOT)).find() || DATE_LIT.matcher(sql).find()) {
            return sql;
        }
        int n = Math.max(1, Math.min(maxRows, 10000));
        return "SELECT * FROM (\n" + sql + "\n) metric_lim LIMIT " + n;
    }
}
