package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 即席扫描治理（Phase A：启发式；正式规则可迁 querygov）
 * <p>限额口径：adhoc 默认 10GB；平台硬顶 50GB（特殊队列/审批）。</p>
 */
public final class CpQueryScanGuard {

    /** adhoc 默认扫描上限：10 GiB */
    public static final long ADHOC_DEFAULT_SCAN_BYTES = 10L * 1024 * 1024 * 1024;
    /** 平台硬顶：50 GiB */
    public static final long PLATFORM_HARD_SCAN_BYTES = 50L * 1024 * 1024 * 1024;
    /** Trino session 名；类型为 DataSize（见 io.airlift.units.DataSize） */
    public static final String QUERY_MAX_SCAN_PHYSICAL_BYTES = "query_max_scan_physical_bytes";
    private static final long KIB = 1024L;
    private static final long MIB = KIB * 1024;
    private static final long GIB = MIB * 1024;
    private static final long TIB = GIB * 1024;

    private static final Pattern FROM = Pattern.compile("\\bfrom\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT = Pattern.compile("\\blimit\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTITION = Pattern.compile("\\b(dt|partition)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ODS = Pattern.compile("\\bods_", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|truncate|alter|create)\\b", Pattern.CASE_INSENSITIVE);

    private CpQueryScanGuard() {
    }

    /**
     * @return null=通过；否则为阻断原因
     */
    public static String blockReason(String sql) {
        if (StrUtil.isBlank(sql)) {
            return "SQL 为空";
        }
        String s = sql.trim();
        if (WRITE.matcher(s).find()) {
            return "即席仅允许只读查询，禁止写语句";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        boolean hasFrom = FROM.matcher(lower).find();
        boolean hasWhere = WHERE.matcher(lower).find();
        boolean hasLimit = LIMIT.matcher(lower).find();
        boolean hasPartition = PARTITION.matcher(lower).find();
        if (hasFrom && !hasWhere && !hasLimit) {
            return "疑似无分区过滤 / 全表扫描（缺 WHERE 且无 LIMIT）";
        }
        if (hasFrom && ODS.matcher(lower).find() && !hasPartition && !hasLimit) {
            return "ODS 表缺少分区过滤（dt/partition）且无 LIMIT";
        }
        return null;
    }

    /**
     * 扫描字节是否超过当前会话限额。
     *
     * @param elevated true 时使用硬顶 50GB，否则默认 10GB
     */
    public static String scanOverLimitReason(Long scanBytes, boolean elevated) {
        if (scanBytes == null || scanBytes < 0) {
            return null;
        }
        long limit = elevated ? PLATFORM_HARD_SCAN_BYTES : ADHOC_DEFAULT_SCAN_BYTES;
        if (scanBytes <= limit) {
            return null;
        }
        String limitLabel = elevated ? "50GB（硬顶）" : "10GB（adhoc 默认）";
        return "扫描量超过限额 " + limitLabel + "，请缩小分区、加 LIMIT 或申请加速表 / 提升配额";
    }

    public static boolean isScanDanger(Long scanBytes, boolean elevated) {
        return scanOverLimitReason(scanBytes, elevated) != null;
    }

    /**
     * Trino 455 DataSize session 值。airlift {@code DataSize.valueOf} 要求带单位
     *（B / kB / MB / GB / TB / PB，1024 进制），拒绝裸字节整数如 {@code 10737418240}。
     */
    public static String toTrinoDataSize(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("scan bytes must be >= 0");
        }
        if (bytes == 0) {
            return "0B";
        }
        if (bytes % TIB == 0) {
            return (bytes / TIB) + "TB";
        }
        if (bytes % GIB == 0) {
            return (bytes / GIB) + "GB";
        }
        if (bytes % MIB == 0) {
            return (bytes / MIB) + "MB";
        }
        if (bytes % KIB == 0) {
            return (bytes / KIB) + "kB";
        }
        return bytes + "B";
    }

    /** session 属性解码失败（值格式非法），不是扫描超限。 */
    public static boolean isInvalidSessionProperty(String msg) {
        if (StrUtil.isBlank(msg)) {
            return false;
        }
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("is invalid")
                || m.contains("invalid session property")
                || m.contains("invalid_session_property");
    }

    /**
     * 引擎/客户端真正因扫描量超限失败。不含「property is invalid」误报。
     */
    public static boolean isEngineScanLimitExceeded(String msg) {
        if (StrUtil.isBlank(msg) || isInvalidSessionProperty(msg)) {
            return false;
        }
        String m = msg.toLowerCase(Locale.ROOT);
        if (m.contains("扫描量超过")) {
            return true;
        }
        if (m.contains("exceeded_scan_limit")) {
            return true;
        }
        return m.contains("exceeded") && m.contains("scan")
                && (m.contains("physical") || m.contains("limit"));
    }

    public static Map<String, Object> blockedPayload(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blocked", true);
        m.put("status", "blocked");
        m.put("statusLabel", "⚠ " + reason);
        m.put("message", reason);
        m.put("columns", java.util.List.of());
        m.put("rows", java.util.List.of());
        m.put("rowCount", 0);
        m.put("scanLimitBytes", ADHOC_DEFAULT_SCAN_BYTES);
        m.put("scanHardLimitBytes", PLATFORM_HARD_SCAN_BYTES);
        return m;
    }
}
