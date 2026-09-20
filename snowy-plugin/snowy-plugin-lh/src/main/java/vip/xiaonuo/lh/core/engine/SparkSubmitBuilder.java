package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

/**
 * Spark Worker 侧 spark-submit / spark-sql 脚本。
 * <p>SQL 保留 {@code ${LH_JDBC_*}} 占位，运行前 envsubst / sed 展开（与 FlinkSubmitBuilder 一致）。</p>
 */
public final class SparkSubmitBuilder {

    private SparkSubmitBuilder() {
    }

    public static String buildShell(IgEtlNode n, String sql, JSONObject conf) {
        String jar = first(conf, "jar", "sparkJar", "appJar");
        String mainClass = first(conf, "mainClass", "entryClass");
        String master = first(conf, "master", "sparkMaster");
        String queue = first(conf, "queue");

        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\nset -euo pipefail\n");
        sb.append("NODE_KEY='").append(n.getNodeKey()).append("'\n");
        sb.append("export LH_JDBC_USER=\"${LH_JDBC_USER:-${LH_READER_JDBC_USER:-}}\"\n");
        sb.append("export LH_JDBC_PASSWORD=\"${LH_JDBC_PASSWORD:-${LH_READER_JDBC_PASSWORD:-}}\"\n");
        sb.append("export LH_JDBC_URL=\"${LH_JDBC_URL:-${LH_READER_JDBC_URL:-}}\"\n");
        sb.append("MASTER=\"${LH_SPARK_MASTER:-").append(StrUtil.blankToDefault(master, "local[*]")).append("}\"\n");
        sb.append("JARS=\"${LH_SPARK_JARS:-/opt/spark/jars/mysql-connector-j-8.0.33.jar}\"\n");
        sb.append("echo \"[lh-spark] node=$NODE_KEY master=$MASTER\"\n");

        if (StrUtil.isNotBlank(jar)) {
            sb.append("JAR='").append(jar).append("'\n");
            sb.append("MAIN='").append(StrUtil.blankToDefault(mainClass, "")).append("'\n");
            sb.append("QUEUE='").append(StrUtil.blankToDefault(queue, "default")).append("'\n");
            sb.append("spark-submit --master \"$MASTER\" --deploy-mode cluster \\\n");
            sb.append("  --queue \"$QUEUE\" \\\n");
            sb.append("  ${MAIN:+--class \"$MAIN\"} \\\n");
            sb.append("  \"$JAR\" ${LH_SPARK_ARGS:-}\n");
        } else if (StrUtil.isNotBlank(sql)) {
            sb.append("SQL_FILE=$(mktemp /tmp/lh-spark-${NODE_KEY}-XXXX.sql)\n");
            sb.append("cat > \"$SQL_FILE\" <<'LH_SPARK_SQL_EOF'\n");
            sb.append(sql).append('\n');
            sb.append("LH_SPARK_SQL_EOF\n");
            LhEnvPlaceholderExpand.appendExpandFile(sb, "SQL_FILE",
                    "LH_JDBC_USER", "LH_JDBC_PASSWORD", "LH_JDBC_URL");
            sb.append("SPARK_SQL_BIN=\"${SPARK_HOME:-/opt/spark}/bin/spark-sql\"\n");
            sb.append("if [ ! -x \"$SPARK_SQL_BIN\" ]; then SPARK_SQL_BIN=$(command -v spark-sql || true); fi\n");
            sb.append("if [ -z \"$SPARK_SQL_BIN\" ]; then echo '[lh-spark] spark-sql not found'; exit 127; fi\n");
            // DS 执行目录常对租户不可写；Spark 本地 metastore/derby 必须落可写路径（/tmp 须 1777）
            sb.append("SPARK_WORK=$(mktemp -d /tmp/lh-spark-work-XXXXXX)\n");
            sb.append("export SPARK_LOCAL_DIRS=\"$SPARK_WORK\"\n");
            sb.append("cd \"$SPARK_WORK\"\n");
            // 过滤 Application Id 行：部分 DS 版本会扫日志做容错重派，导致 SHELL 清洗 SUCCESS 后立刻再跑
            sb.append("set +e\n");
            sb.append("set -o pipefail\n");
            sb.append("\"$SPARK_SQL_BIN\" --master \"$MASTER\" --jars \"$JARS\" \\\n");
            sb.append("  --conf spark.ui.showConsoleProgress=false \\\n");
            sb.append("  --conf spark.sql.warehouse.dir=\"$SPARK_WORK/warehouse\" \\\n");
            sb.append("  --conf spark.driver.extraJavaOptions=\"-Dderby.system.home=$SPARK_WORK\" \\\n");
            sb.append("  -f \"$SQL_FILE\" 2>&1 | sed -e '/Application Id:/d' -e '/Spark master:/d'\n");
            sb.append("rc=${PIPESTATUS[0]}\n");
            sb.append("set +o pipefail\n");
            sb.append("set -e\n");
            sb.append("rm -f \"$SQL_FILE\" \"$SQL_FILE.bak\"\n");
            sb.append("rm -rf \"$SPARK_WORK\"\n");
            sb.append("echo \"[lh-spark] exit=$rc\"\n");
            sb.append("exit $rc\n");
        } else {
            sb.append("echo '[lh-spark] no jar/sql'; exit 1\n");
        }
        return sb.toString();
    }

    /** 无 IgEtlNode 时按 nodeKey 生成（DS 发布热路径）。 */
    public static String buildSqlShell(String nodeKey, String sql, String master) {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey(StrUtil.blankToDefault(nodeKey, "spark"));
        JSONObject conf = new JSONObject();
        conf.set("master", StrUtil.blankToDefault(master, "local[*]"));
        return buildShell(n, sql, conf);
    }

    private static String first(JSONObject conf, String... keys) {
        if (conf == null) {
            return null;
        }
        for (String k : keys) {
            String v = conf.getStr(k);
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
