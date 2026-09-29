package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Iceberg Spark Procedure SQL（对齐官方 CALL catalog.system.*）
 */
public final class GovLcProcedureSql {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private GovLcProcedureSql() {
    }

    public static String expire(String catalog, String tableFqn, GovLcPolicy policy) {
        return expire(catalog, tableFqn, policy, null);
    }

    /**
     * @param retainLastOverride 非空时为合规定向过期（只接受调用方已校验的值，通常为 1），
     *                           older_than 取当前时刻，从而只留最近 N 个快照。
     */
    public static String expire(String catalog, String tableFqn, GovLcPolicy policy, Integer retainLastOverride) {
        int retainLast;
        String older;
        if (retainLastOverride != null && retainLastOverride > 0) {
            retainLast = retainLastOverride;
            older = TS.format(Instant.now());
        } else {
            int retain = policy != null && policy.getMinSnapshots() != null ? policy.getMinSnapshots() : 5;
            int keepCount = policy != null && policy.getKeepCount() != null ? policy.getKeepCount() : 20;
            retain = Math.max(retain, 1);
            int days = policy != null && policy.getKeepDays() != null ? policy.getKeepDays() : 7;
            older = TS.format(Instant.now().minusSeconds(days * 86400L));
            retainLast = Math.min(keepCount, Math.max(retain, 5));
        }
        return "CALL " + catalog + ".system.expire_snapshots("
                + "table => '" + escape(tableFqn) + "', "
                + "older_than => TIMESTAMP '" + older + "', "
                + "retain_last => " + retainLast + ")";
    }

    public static String rewrite(String catalog, String tableFqn, GovLcPolicy policy) {
        int targetMb = policy != null && policy.getTargetFileMb() != null ? policy.getTargetFileMb() : 256;
        long targetBytes = Math.max(32, targetMb) * 1024L * 1024L;
        return "CALL " + catalog + ".system.rewrite_data_files("
                + "table => '" + escape(tableFqn) + "', "
                + "options => map('target-file-size-bytes', '" + targetBytes + "'))";
    }

    public static String removeOrphan(String catalog, String tableFqn, GovLcPolicy policy, boolean dryRun) {
        int olderDays = policy != null && policy.getOrphanOlderDays() != null ? policy.getOrphanOlderDays() : 7;
        if (olderDays < 7) {
            olderDays = 7;
        }
        String older = TS.format(Instant.now().minusSeconds(olderDays * 86400L));
        return "CALL " + catalog + ".system.remove_orphan_files("
                + "table => '" + escape(tableFqn) + "', "
                + "older_than => TIMESTAMP '" + older + "', "
                + "dry_run => " + dryRun + ")";
    }

    /**
     * 日作业第 4 步：候选分区列表（读 Iceberg {@code .partitions} 元数据）+ 冷桶迁移模板。
     * 默认 dry-run：只写出候选清单 SQL；物理迁冷桶须人工确认后改 {@code dry_run => false}。
     * 对齐 doc/生命周期.md §5.2。
     */
    public static String partitionExpirePlaceholder(String tableFqn, GovLcPolicy policy) {
        return partitionArchiveStep(tableFqn, policy, "s3a://archive/iceberg");
    }

    public static String partitionArchiveStep(String tableFqn, GovLcPolicy policy, String coldBucketPrefix) {
        Integer days = policy != null ? policy.getPartitionExpireDays() : null;
        if (days == null || days <= 0) {
            return "-- skip partition archive for " + escape(tableFqn) + " (no partition_expire_days)";
        }
        String fqn = escape(tableFqn);
        String cold = escape(StrUtil.blankToDefault(coldBucketPrefix, "s3a://archive/iceberg"));
        String cutoff = TS.format(Instant.now().minusSeconds(days * 86400L));
        List<String> lines = new ArrayList<>();
        lines.add("-- §5.2 archive candidates: table=" + fqn + " older_than_days=" + days
                + " cutoff_utc=" + cutoff);
        lines.add("-- 1) list candidate partitions from Iceberg metadata (no data scan)");
        lines.add("CREATE OR REPLACE TEMP VIEW _lh_lc_archive_cand_" + safeIdent(tableFqn) + " AS "
                + "SELECT '" + fqn + "' AS table_fqn, partition, file_count, "
                + "CAST(NULL AS BIGINT) AS total_size_bytes "
                + "FROM " + fqn + ".partitions "
                + "WHERE 1=1 /* filter older_than in job params / partition transforms */");
        lines.add("SELECT * FROM _lh_lc_archive_cand_" + safeIdent(tableFqn));
        lines.add("-- 2) cold-bucket migrate TEMPLATE (dry-run；确认后去掉 dry_run 注释并改写 location)");
        lines.add("-- CALL " + "spark_catalog" + ".system.rewrite_data_files("
                + "table => '" + fqn + "', "
                + "options => map('target-location', '" + cold + "/" + fqn.replace('.', '/') + "')) "
                + "/* dry_run: do not execute until approved */");
        lines.add("SELECT COUNT(*) AS candidate_partitions FROM _lh_lc_archive_cand_" + safeIdent(tableFqn));
        return String.join("\n", lines);
    }

    public static String script(List<String> statements) {
        List<String> lines = new ArrayList<>();
        lines.add("-- lh lifecycle iceberg procedures");
        for (String s : statements) {
            if (StrUtil.isBlank(s)) {
                continue;
            }
            String t = s.trim();
            if (t.startsWith("--")) {
                lines.add(t);
            } else {
                lines.add(t.endsWith(";") ? t : t + ";");
            }
        }
        return String.join("\n", lines);
    }

    public static String safeIdent(String raw) {
        String s = StrUtil.blankToDefault(raw, "x").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_");
        if (s.length() > 48) {
            s = s.substring(0, 48);
        }
        return s;
    }

    private static String escape(String tableFqn) {
        return StrUtil.blankToDefault(tableFqn, "").replace("'", "''");
    }
}
