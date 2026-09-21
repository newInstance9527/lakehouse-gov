package vip.xiaonuo.lh.modular.ai.support;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AI run-sql 只读校验（SELECT/WITH/EXPLAIN）
 */
public final class AiSqlGuard {

    private static final Pattern WRITE = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|truncate|alter|create|grant|revoke|call)\\b",
            Pattern.CASE_INSENSITIVE);

    private AiSqlGuard() {
    }

    /**
     * @return null=通过；否则阻断原因
     */
    public static String blockReason(String sql) {
        if (StrUtil.isBlank(sql)) {
            return "SQL 为空";
        }
        String s = sql.trim();
        // 去掉末尾分号
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (WRITE.matcher(s).find()) {
            return "仅允许只读语句（SELECT/WITH/EXPLAIN）";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        boolean ok = lower.startsWith("select")
                || lower.startsWith("with")
                || lower.startsWith("explain")
                || lower.startsWith("show")
                || lower.startsWith("describe")
                || lower.startsWith("desc ");
        if (!ok) {
            return "仅允许 SELECT / WITH / EXPLAIN 开头的只读 SQL";
        }
        return null;
    }
}
