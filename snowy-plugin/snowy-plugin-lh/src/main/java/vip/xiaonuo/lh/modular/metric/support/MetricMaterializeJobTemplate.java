package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 指标物化作业 SQL 模板：按 metric_code 写 ADS Iceberg / ClickHouse，并附分区对账抽样。
 * <p>作业本体内不跑 Spark；由 DS SHELL/spark-sql 执行生成脚本。</p>
 */
public final class MetricMaterializeJobTemplate {

    private static final Pattern SAFE_IDENT = Pattern.compile("^[A-Za-z0-9_\\-\\.]+$");

    private MetricMaterializeJobTemplate() {
    }

    public static String workflowName(String metricCode) {
        return "job.metric.mat." + safeCode(metricCode).toLowerCase(Locale.ROOT);
    }

    public static String reconWorkflowName(String metricCode) {
        return "job.metric.recon." + safeCode(metricCode).toLowerCase(Locale.ROOT);
    }

    public static String safeCode(String metricCode) {
        String c = StrUtil.blankToDefault(metricCode, "unknown").trim();
        if (!SAFE_IDENT.matcher(c).matches()) {
            throw new IllegalArgumentException("非法 metric_code: " + metricCode);
        }
        return c;
    }

    /**
     * Iceberg ADS 物化：将编译 SQL 结果写入目标表（按日分区）。
     */
    public static String icebergInsertSql(String metricCode, String ver, String targetTable,
                                          String compiledSelectSql, String partitionDt) {
        String code = safeCode(metricCode);
        String table = MetricMaterializeRewrite.assertSafeTable(targetTable);
        String src = StrUtil.blankToDefault(compiledSelectSql, "SELECT 1 AS metric_value, DATE '" + dt(partitionDt) + "' AS dt");
        return "-- metric_code=" + code + " ver=" + StrUtil.blankToDefault(ver, "-")
                + " engine=iceberg\n"
                + "INSERT INTO " + table + "\n"
                + "SELECT * FROM (\n"
                + src.trim().replaceAll(";\\s*$", "") + "\n"
                + ") _lh_mat\n"
                + "WHERE 1=1 /* partition dt=" + dt(partitionDt) + " */;\n";
    }

    /**
     * ClickHouse 热表物化（经 Trino→CK 或 spark-sql 方言由 Worker 侧适配）。
     */
    public static String clickHouseInsertSql(String metricCode, String ver, String targetTable,
                                             String compiledSelectSql, String partitionDt) {
        String code = safeCode(metricCode);
        String table = MetricMaterializeRewrite.assertSafeTable(targetTable);
        String src = StrUtil.blankToDefault(compiledSelectSql, "SELECT 1 AS metric_value, toDate('" + dt(partitionDt) + "') AS dt");
        return "-- metric_code=" + code + " ver=" + StrUtil.blankToDefault(ver, "-")
                + " engine=clickhouse\n"
                + "INSERT INTO " + table + "\n"
                + "SELECT * FROM (\n"
                + src.trim().replaceAll(";\\s*$", "") + "\n"
                + ") AS _lh_mat;\n";
    }

    /**
     * 分区行数对账抽样 SQL（湖 vs CK；结果由门户 /recon/partition/run 落库）。
     */
    public static String reconCountSql(String lakeTable, String ckTable, String partitionDt) {
        String lake = MetricMaterializeRewrite.assertSafeTable(lakeTable);
        String ck = MetricMaterializeRewrite.assertSafeTable(ckTable);
        String d = dt(partitionDt);
        return "-- partition recon lake vs ck dt=" + d + "\n"
                + "SELECT 'iceberg' AS side, COUNT(*) AS rows FROM " + lake + " WHERE dt = DATE '" + d + "'\n"
                + "UNION ALL\n"
                + "SELECT 'clickhouse' AS side, COUNT(*) AS rows FROM " + ck + " WHERE dt = DATE '" + d + "';\n";
    }

    public static String dualWriteShellComment(String metricCode, String iceTable, String ckTable) {
        return "# metric_code=" + safeCode(metricCode)
                + " ice=" + StrUtil.blankToDefault(iceTable, "-")
                + " ck=" + StrUtil.blankToDefault(ckTable, "-")
                + "\n# bind metric_code MUST; Flink realtime jobs same rule\n";
    }

    private static String dt(String partitionDt) {
        if (StrUtil.isBlank(partitionDt)) {
            throw new IllegalArgumentException("partitionDt 不能为空");
        }
        return partitionDt.trim();
    }
}
