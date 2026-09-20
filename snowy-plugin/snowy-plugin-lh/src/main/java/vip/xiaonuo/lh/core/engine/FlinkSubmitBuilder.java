package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

/**
 * Flink Worker 侧提交脚本。
 * <p>本环境 Flink 在 Docker（{@code lh-flink-jm}），DS Worker 无 {@code sql-client.sh} PATH。
 * 脚本按序尝试：docker exec → FLINK_HOME → SQL Gateway REST。</p>
 */
public final class FlinkSubmitBuilder {

    private FlinkSubmitBuilder() {
    }

    public static String buildShell(IgEtlNode n, String sql, JSONObject conf) {
        String jarId = first(conf, "jarId", "flinkJarId");
        String entry = first(conf, "entryClass", "mainClass", "org.apache.flink.client.cli.CliFrontend");
        String progArgs = first(conf, "programArgs", "args");
        String container = StrUtil.blankToDefault(first(conf, "flinkContainer", "flinkJmContainer"), "lh-flink-jm");

        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\nset -euo pipefail\n");
        sb.append("NODE_KEY='").append(n.getNodeKey()).append("'\n");
        sb.append("echo \"[lh-flink] node=$NODE_KEY\"\n");
        // 兼容单源 / 拆分凭证
        sb.append("export LH_JDBC_USER=\"${LH_JDBC_USER:-${LH_READER_JDBC_USER:-}}\"\n");
        sb.append("export LH_JDBC_PASSWORD=\"${LH_JDBC_PASSWORD:-${LH_READER_JDBC_PASSWORD:-}}\"\n");
        sb.append("export LH_JDBC_URL=\"${LH_JDBC_URL:-${LH_READER_JDBC_URL:-}}\"\n");
        sb.append("SQL_FILE=$(mktemp /tmp/lh-flink-${NODE_KEY}-XXXX.sql)\n");
        sb.append("cat > \"$SQL_FILE\" <<'LH_FLINK_SQL_EOF'\n");
        sb.append(StrUtil.blankToDefault(sql, "SELECT 1")).append('\n');
        sb.append("LH_FLINK_SQL_EOF\n");
        // 展开 ${LH_JDBC_*}（URL/密码含 & 时禁止裸 sed）
        LhEnvPlaceholderExpand.appendExpandFile(sb, "SQL_FILE",
                "LH_JDBC_USER", "LH_JDBC_PASSWORD", "LH_JDBC_URL", "LH_HOST", "LH_DATABASE");
        sb.append("echo \"[lh-flink] sql file=$SQL_FILE\"\n");
        sb.append("head -n 5 \"$SQL_FILE\" || true\n");

        if (StrUtil.isNotBlank(jarId)) {
            sb.append("FLINK_URL=\"${LH_FLINK_URL:-}\"\n");
            sb.append("if [ -z \"$FLINK_URL\" ]; then echo '[lh-flink] LH_FLINK_URL missing'; exit 1; fi\n");
            sb.append("JAR_ID='").append(jarId).append("'\n");
            sb.append("ENTRY='").append(StrUtil.blankToDefault(entry, "")).append("'\n");
            sb.append("ARGS='").append(StrUtil.blankToDefault(progArgs, "")).append("'\n");
            sb.append("BODY=$(printf '{\"entryClass\":\"%s\",\"programArgs\":\"%s\",\"parallelism\":1}' \"$ENTRY\" \"$ARGS\")\n");
            sb.append("RESP=$(curl -sS -u \"${LH_FLINK_USER:-}:${LH_FLINK_PASSWORD:-}\" \\\n");
            sb.append("  -H 'Content-Type: application/json' -X POST \\\n");
            sb.append("  -d \"$BODY\" \"${FLINK_URL%/}/jars/${JAR_ID}/run\")\n");
            sb.append("echo \"$RESP\"\n");
            sb.append("echo \"$RESP\" | grep -qiE 'jobid|jid' || { echo '[lh-flink] jar submit failed'; exit 1; }\n");
            sb.append("rm -f \"$SQL_FILE\" \"$SQL_FILE.bak\"\n");
            return sb.toString();
        }

        // —— SQL 路径：docker exec → FLINK_HOME → SQL Gateway ——
        sb.append("FLINK_CONTAINER=\"${LH_FLINK_CONTAINER:-").append(container).append("}\"\n");
        sb.append("FLINK_HOME_DIR=\"${FLINK_HOME:-/opt/flink}\"\n");
        sb.append("SQL_CLIENT=\"\"\n");
        sb.append("if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx \"$FLINK_CONTAINER\"; then\n");
        sb.append("  echo \"[lh-flink] via docker exec $FLINK_CONTAINER\"\n");
        sb.append("  docker cp \"$SQL_FILE\" \"$FLINK_CONTAINER:/tmp/lh-flink.sql\"\n");
        sb.append("  docker exec \"$FLINK_CONTAINER\" /opt/flink/bin/sql-client.sh -f /tmp/lh-flink.sql\n");
        sb.append("  rc=$?\n");
        sb.append("  rm -f \"$SQL_FILE\" \"$SQL_FILE.bak\"\n");
        sb.append("  exit $rc\n");
        sb.append("fi\n");
        sb.append("if [ -x \"$FLINK_HOME_DIR/bin/sql-client.sh\" ]; then\n");
        sb.append("  SQL_CLIENT=\"$FLINK_HOME_DIR/bin/sql-client.sh\"\n");
        sb.append("elif command -v sql-client.sh >/dev/null 2>&1; then\n");
        sb.append("  SQL_CLIENT=$(command -v sql-client.sh)\n");
        sb.append("fi\n");
        sb.append("if [ -n \"$SQL_CLIENT\" ]; then\n");
        sb.append("  echo \"[lh-flink] via $SQL_CLIENT\"\n");
        sb.append("  OUT=$(\"$SQL_CLIENT\" -f \"$SQL_FILE\" 2>&1) || true\n");
        sb.append("  echo \"$OUT\"\n");
        sb.append("  JID=$(echo \"$OUT\" | sed -n 's/.*Job ID: \\([0-9a-fA-F]\\{32\\}\\).*/\\1/p' | tail -n1)\n");
        sb.append("  if [ -n \"$JID\" ]; then\n");
        sb.append("    FLINK_REST=\"${LH_FLINK_URL:-http://flink-jobmanager:8081}\"\n");
        sb.append("    echo \"[lh-flink] wait job $JID via $FLINK_REST\"\n");
        sb.append("    for i in $(seq 1 120); do\n");
        sb.append("      ST=$(curl -sS \"${FLINK_REST%/}/jobs/${JID}\" 2>/dev/null | sed -n 's/.*\\\"state\\\"[[:space:]]*:[[:space:]]*\\\"\\([A-Z_]*\\)\\\".*/\\1/p' | head -n1)\n");
        sb.append("      echo \"[lh-flink] state=$ST try=$i\"\n");
        sb.append("      case \"$ST\" in\n");
        sb.append("        FINISHED) rm -f \"$SQL_FILE\" \"$SQL_FILE.bak\"; exit 0 ;;\n");
        sb.append("        FAILED|CANCELED|FAILING) rm -f \"$SQL_FILE\" \"$SQL_FILE.bak\"; exit 1 ;;\n");
        sb.append("      esac\n");
        sb.append("      sleep 2\n");
        sb.append("    done\n");
        sb.append("    echo '[lh-flink] wait timeout'; rm -f \"$SQL_FILE\" \"$SQL_FILE.bak\"; exit 1\n");
        sb.append("  fi\n");
        sb.append("  echo \"$OUT\" | grep -qiE 'Execute statement succeed|successfully submitted' \\\n");
        sb.append("    && { rm -f \"$SQL_FILE\" \"$SQL_FILE.bak\"; exit 0; } \\\n");
        sb.append("    || { rm -f \"$SQL_FILE\" \"$SQL_FILE.bak\"; exit 1; }\n");
        sb.append("fi\n");

        sb.append("FLINK_URL=\"${LH_FLINK_URL:-}\"\n");
        sb.append("GW=\"${LH_FLINK_SQL_GATEWAY:-}\"\n");
        sb.append("if [ -z \"$GW\" ] && [ -n \"$FLINK_URL\" ]; then GW=\"${FLINK_URL%/}/v1/sessions\"; fi\n");
        sb.append("if [ -n \"$GW\" ] && command -v curl >/dev/null 2>&1; then\n");
        sb.append("  echo \"[lh-flink] via SQL Gateway $GW\"\n");
        sb.append("  SQL=$(cat \"$SQL_FILE\" | sed ':a;N;$!ba;s/\\n/\\\\n/g' | sed 's/\"/\\\\\"/g')\n");
        sb.append("  SESS=$(curl -sS -u \"${LH_FLINK_USER:-}:${LH_FLINK_PASSWORD:-}\" \\\n");
        sb.append("    -H 'Content-Type: application/json' -X POST -d '{}' \"$GW\" || true)\n");
        sb.append("  SID=$(echo \"$SESS\" | sed -n 's/.*\"sessionHandle\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p')\n");
        sb.append("  if [ -z \"$SID\" ]; then echo \"[lh-flink] sql-gateway session failed: $SESS\"; rm -f \"$SQL_FILE\"; exit 1; fi\n");
        sb.append("  RESP=$(curl -sS -u \"${LH_FLINK_USER:-}:${LH_FLINK_PASSWORD:-}\" \\\n");
        sb.append("    -H 'Content-Type: application/json' -X POST \\\n");
        sb.append("    -d \"{\\\"statement\\\":\\\"$SQL\\\"}\" \\\n");
        sb.append("    \"${GW%/}/$SID/statements\")\n");
        sb.append("  echo \"$RESP\"\n");
        sb.append("  rm -f \"$SQL_FILE\" \"$SQL_FILE.bak\"\n");
        sb.append("  echo \"$RESP\" | grep -qiE 'error|exception' && exit 1 || exit 0\n");
        sb.append("fi\n");

        sb.append("echo \"[lh-flink] ERROR: 找不到 sql-client.sh，且无法 docker exec ${FLINK_CONTAINER} / SQL Gateway\"\n");
        sb.append("echo \"[lh-flink] 请在 DS Worker 配置 FLINK_HOME，或挂载 docker.sock 并确保容器 ${FLINK_CONTAINER} 可达，或配置 LH_FLINK_SQL_GATEWAY\"\n");
        sb.append("rm -f \"$SQL_FILE\" \"$SQL_FILE.bak\"\n");
        sb.append("exit 127\n");
        return sb.toString();
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
