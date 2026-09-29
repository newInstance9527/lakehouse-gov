/**
 * Flink 旁路可粘贴模板：在 ProcessFunction / RichMapFunction 的 open()/定时器里调用 report(...)。
 * <p>无 Flink 依赖，仅 JDK HttpURLConnection；放入作业 fat-jar 或单独工具类即可。</p>
 * <p>配套：POST {govBase}/lh/quality/rules/stream-probe → VM lh_dq_stream_* / 夜莺。</p>
 */
public final class LhDqStreamProbeReporter {

    private LhDqStreamProbeReporter() {
    }

    public static final class Probe {
        public String govBase = "http://127.0.0.1:82";
        public String token;
        public String ws = "default";
        public String ruleId;
        public String ruleCode;
        public String tableName;
        public String jobId = "flink-job";
        public boolean pass = true;
        public Double okPct;
        public Double failRatio;
        public Long lagMs;
        public boolean persistRun = true;
        public String message;
    }

    /** soft-fail：网络/门户失败返回 false，不抛。 */
    public static boolean report(Probe p) {
        if (p == null || p.govBase == null || p.govBase.isBlank()) {
            return false;
        }
        try {
            String base = p.govBase.replaceAll("/+$", "");
            java.net.URI uri = java.net.URI.create(base + "/lh/quality/rules/stream-probe");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) uri.toURL().openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5_000);
            c.setReadTimeout(15_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (p.token != null && !p.token.isBlank()) {
                c.setRequestProperty("token", p.token.trim());
            }
            String json = toJson(p);
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            System.err.println("[lh-dq-stream] report soft-fail: " + e.getMessage());
            return false;
        }
    }

    private static String toJson(Probe p) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "ws", p.ws, true);
        field(sb, "ruleId", p.ruleId, false);
        field(sb, "ruleCode", p.ruleCode, false);
        field(sb, "tableName", p.tableName, false);
        field(sb, "jobId", p.jobId, false);
        sb.append("\"pass\":").append(p.pass);
        if (p.okPct != null) {
            sb.append(",\"okPct\":").append(p.okPct);
        }
        if (p.failRatio != null) {
            sb.append(",\"failRatio\":").append(p.failRatio);
        }
        if (p.lagMs != null) {
            sb.append(",\"lagMs\":").append(p.lagMs);
        }
        sb.append(",\"persistRun\":").append(p.persistRun);
        field(sb, "message", p.message, false);
        sb.append('}');
        return sb.toString();
    }

    private static void field(StringBuilder sb, String k, String v, boolean first) {
        if (v == null || v.isBlank()) {
            return;
        }
        if (!first || sb.charAt(sb.length() - 1) != '{') {
            sb.append(',');
        }
        sb.append('"').append(k).append("\":\"").append(esc(v)).append('"');
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /*
     * —— Flink 粘贴示例（需作业侧依赖 flink-streaming-java）——
     *
     * public class DqProbeFunction extends KeyedProcessFunction<String, Row, Row> {
     *   private transient LhDqStreamProbeReporter.Probe probe;
     *   @Override public void open(Configuration conf) {
     *     probe = new LhDqStreamProbeReporter.Probe();
     *     probe.govBase = getRuntimeContext().getTaskManagerConfiguration()
     *         ... or System.getenv("LH_GOV_URL");
     *     probe.ruleCode = "NULL_CHECK";
     *     probe.tableName = "ods_trade.s_order";
     *     probe.jobId = getRuntimeContext().getJobId().toString();
     *     // 注册 ProcessingTime 定时器，每 60s 聚合窗口失败率后 report(probe)
     *   }
     * }
     */
}
