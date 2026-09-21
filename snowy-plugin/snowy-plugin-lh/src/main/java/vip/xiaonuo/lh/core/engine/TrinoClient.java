/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trino 客户端（凭证来自 Vault）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class TrinoClient {

    /** 运行中 queryId → nextUri / cancel 用 */
    private final ConcurrentHashMap<String, String> runningNextUri = new ConcurrentHashMap<>();

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    /**
     * 人查必须代执行已映射主体；作业只使用服务账号，且不能冒充门户用户。
     */
    private String resolveEndUser(ExecuteOptions o, String serviceUser) {
        ExecuteOptions.Identity identity = o.identity == null ? ExecuteOptions.Identity.JOB : o.identity;
        if (identity == ExecuteOptions.Identity.JOB) {
            if (StrUtil.isNotBlank(o.trinoUser) && !serviceUser.equalsIgnoreCase(o.trinoUser)) {
                throw new CommonException("作业身份不能冒充门户用户");
            }
            return serviceUser;
        }
        if (StrUtil.isBlank(o.trinoUser) || serviceUser.equalsIgnoreCase(o.trinoUser)) {
            throw new CommonException("人查必须使用已映射的 Gravitino 主体，禁止落到服务账号 " + serviceUser);
        }
        if (!lhProperties.getTrino().isImpersonate()) {
            throw new CommonException("lh.trino.impersonate 未开启，拒绝以服务账号代替用户执行");
        }
        return o.trinoUser;
    }

    /**
     * 执行 SQL（兼容旧调用：不截断、默认 catalog）
     */
    public Map<String, Object> execute(String sql) {
        return execute(sql, ExecuteOptions.defaults());
    }

    /**
     * 执行 SQL（可截断行数、指定 catalog/schema、取消）
     */
    public Map<String, Object> execute(String sql, ExecuteOptions opts) {
        ExecuteOptions o = opts == null ? ExecuteOptions.defaults() : opts;
        String base = StrUtil.removeSuffix(lhProperties.getTrino().getUrl(), "/");
        if (StrUtil.isBlank(base)) {
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("columns", List.of());
            degraded.put("rows", List.of());
            degraded.put("rowCount", 0);
            degraded.put("degraded", true);
            degraded.put("message", "lh.trino.url 未配置");
            return degraded;
        }
        Map<String, String> cred = credentialResolver.trino();
        String serviceUser = cred.get("username");
        String password = cred.get("password");
        String endUser = resolveEndUser(o, serviceUser);
        try {
            HttpRequest req = HttpRequest.post(base + "/v1/statement")
                    .header("X-Trino-User", endUser)
                    .header("X-Trino-Source", StrUtil.blankToDefault(o.source, "lakehouse-portal"))
                    .header("X-Trino-Catalog", StrUtil.blankToDefault(o.catalog, "iceberg"))
                    .header("X-Trino-Schema", StrUtil.blankToDefault(o.schema, "default"))
                    .basicAuth(serviceUser, password)
                    .body(sql)
                    .timeout(Math.max(10000, o.timeoutMs));
            if (StrUtil.isNotBlank(o.clientTags)) {
                req.header("X-Trino-Client-Tags", o.clientTags);
            }
            // session：扫描硬顶 + 其它属性
            StringBuilder session = new StringBuilder();
            if (o.maxScanBytes > 0) {
                appendSession(session, "query_max_scan_physical_bytes", String.valueOf(o.maxScanBytes));
            }
            if (o.sessionProps != null) {
                o.sessionProps.forEach((k, v) -> {
                    if (!"query_max_scan_physical_bytes".equals(k)) {
                        appendSession(session, k, v);
                    }
                });
            }
            if (!session.isEmpty()) {
                req.header("X-Trino-Session", session.toString());
            }
            if (lhProperties.getTrino().isInsecureSsl()) {
                trustAll(req);
            }
            HttpResponse resp = req.execute();
            JSONObject body = JSONUtil.parseObj(resp.body());
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> columns = new ArrayList<>();
            throwIfTrinoError(body);
            String trinoId = body.getStr("id");
            String next = body.getStr("nextUri");
            if (StrUtil.isNotBlank(trinoId) && StrUtil.isNotBlank(next)) {
                runningNextUri.put(trinoId, next);
            }
            long processedBytes = 0L;
            long elapsedMs = 0L;
            List<Map<String, Object>> stages = new ArrayList<>();
            StatsSnap snap = collect(body, columns, rows, o.maxRows);
            processedBytes = Math.max(processedBytes, snap.processedBytes);
            elapsedMs = Math.max(elapsedMs, snap.elapsedMs);
            pushStage(stages, o.onProgress, "queued", trinoId, processedBytes, rows.size(), elapsedMs, snap.state);
            boolean truncated = snap.truncated;
            boolean scanHardKilled = false;
            int guard = 0;
            while (StrUtil.isNotBlank(next) && !truncated && guard++ < 200) {
                if (o.maxScanBytes > 0 && processedBytes > o.maxScanBytes) {
                    scanHardKilled = true;
                    if (StrUtil.isNotBlank(trinoId)) {
                        cancelQuiet(trinoId, serviceUser, password, base);
                    }
                    break;
                }
                if (StrUtil.isNotBlank(trinoId)) {
                    runningNextUri.put(trinoId, next);
                }
                HttpRequest nreq = HttpRequest.get(next).timeout(Math.max(10000, o.timeoutMs))
                        .basicAuth(serviceUser, password);
                if (lhProperties.getTrino().isInsecureSsl()) {
                    trustAll(nreq);
                }
                JSONObject page = JSONUtil.parseObj(nreq.execute().body());
                throwIfTrinoError(page);
                snap = collect(page, columns, rows, o.maxRows);
                processedBytes = Math.max(processedBytes, snap.processedBytes);
                elapsedMs = Math.max(elapsedMs, snap.elapsedMs);
                truncated = snap.truncated;
                pushStage(stages, o.onProgress, "running", trinoId, processedBytes, rows.size(), elapsedMs, snap.state);
                next = page.getStr("nextUri");
            }
            if (o.maxScanBytes > 0 && processedBytes > o.maxScanBytes) {
                scanHardKilled = true;
                if (StrUtil.isNotBlank(trinoId)) {
                    cancelQuiet(trinoId, serviceUser, password, base);
                }
            }
            if (truncated && o.cancelOnLimit && StrUtil.isNotBlank(trinoId)) {
                cancelQuiet(trinoId, serviceUser, password, base);
            }
            if (StrUtil.isNotBlank(trinoId)) {
                runningNextUri.remove(trinoId);
            }
            if (scanHardKilled) {
                throw new CommonException("扫描量超过 Trino session 限额 {} 字节，查询已取消", o.maxScanBytes);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", columns);
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            result.put("trinoQueryId", trinoId);
            result.put("scanBytes", processedBytes);
            result.put("elapsedMs", elapsedMs);
            result.put("truncated", truncated);
            result.put("maxScanBytes", o.maxScanBytes > 0 ? o.maxScanBytes : null);
            result.put("stages", stages);
            pushStage(stages, o.onProgress, "finished", trinoId, processedBytes, rows.size(), elapsedMs, "FINISHED");
            return result;
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("columns", List.of());
            degraded.put("rows", List.of());
            degraded.put("rowCount", 0);
            degraded.put("degraded", true);
            degraded.put("message", "Trino暂不可达: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return degraded;
        }
    }

    /**
     * 取消 Trino 查询
     */
    public Map<String, Object> cancel(String trinoQueryId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trinoQueryId", trinoQueryId);
        if (StrUtil.isBlank(trinoQueryId)) {
            out.put("ok", false);
            out.put("message", "trinoQueryId 为空");
            return out;
        }
        String base = StrUtil.removeSuffix(lhProperties.getTrino().getUrl(), "/");
        Map<String, String> cred = credentialResolver.trino();
        String user = cred.get("username");
        String password = cred.get("password");
        try {
            cancelQuiet(trinoQueryId, user, password, base);
            runningNextUri.remove(trinoQueryId);
            out.put("ok", true);
            out.put("message", "cancelled");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
        }
        return out;
    }

    /**
     * Schema 树（Catalog 列表，兼容旧 UI；正式树见 CpQueryService）
     */
    public List<Map<String, Object>> schemaTree() {
        Map<String, Object> res = execute("SHOW CATALOGS");
        List<Map<String, Object>> tree = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) res.getOrDefault("rows", List.of());
        for (Map<String, Object> row : rows) {
            Object cat = row.values().stream().findFirst().orElse(null);
            if (cat != null) {
                tree.add(Map.of("title", String.valueOf(cat), "key", String.valueOf(cat), "isLeaf", false));
            }
        }
        if (tree.isEmpty()) {
            tree.add(Map.of("title", "iceberg", "key", "iceberg", "children", List.of(
                    Map.of("title", "ods_trade", "key", "iceberg.ods_trade"),
                    Map.of("title", "dwd_trade", "key", "iceberg.dwd_trade"),
                    Map.of("title", "dws_trade", "key", "iceberg.dws_trade"),
                    Map.of("title", "ads_trade", "key", "iceberg.ads_trade")
            )));
        }
        return tree;
    }

    private void cancelQuiet(String trinoQueryId, String user, String password, String base) {
        String path = base + "/v1/query/" + trinoQueryId;
        HttpRequest del = HttpRequest.delete(path).basicAuth(user, password).timeout(10000);
        if (lhProperties.getTrino().isInsecureSsl()) {
            trustAll(del);
        }
        del.execute();
    }

    private static void appendSession(StringBuilder sb, String key, String value) {
        if (StrUtil.isBlank(key) || value == null) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(',');
        }
        sb.append(key).append('=').append(value);
    }

    private static void throwIfTrinoError(JSONObject page) {
        if (page != null && page.containsKey("error")) {
            Object msg = page.getByPath("error.message");
            throw new CommonException("Trino错误: {}", msg == null ? "unknown" : msg);
        }
    }

    private StatsSnap collect(JSONObject page, List<String> columns, List<Map<String, Object>> rows, int maxRows) {
        StatsSnap snap = new StatsSnap();
        if (page != null && page.containsKey("stats")) {
            JSONObject stats = page.getJSONObject("stats");
            if (stats != null) {
                snap.processedBytes = stats.getLong("processedBytes", 0L);
                snap.elapsedMs = stats.getLong("elapsedTimeMillis", 0L);
                snap.state = stats.getStr("state");
            }
        }
        if (columns.isEmpty() && page.containsKey("columns")) {
            JSONArray cols = page.getJSONArray("columns");
            for (int i = 0; i < cols.size(); i++) {
                columns.add(cols.getJSONObject(i).getStr("name"));
            }
        }
        if (page.containsKey("data")) {
            JSONArray data = page.getJSONArray("data");
            for (int i = 0; i < data.size(); i++) {
                if (maxRows > 0 && rows.size() >= maxRows) {
                    snap.truncated = true;
                    break;
                }
                JSONArray line = data.getJSONArray(i);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 0; c < columns.size() && c < line.size(); c++) {
                    row.put(columns.get(c), line.get(c));
                }
                rows.add(row);
            }
            if (maxRows > 0 && rows.size() >= maxRows) {
                snap.truncated = true;
            }
        }
        return snap;
    }

    private static void pushStage(List<Map<String, Object>> stages, ProgressListener listener,
                                  String phase, String trinoId, long bytes, int rows, long ms, String state) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("phase", phase);
        s.put("trinoQueryId", trinoId);
        s.put("scanBytes", bytes);
        s.put("rowCount", rows);
        s.put("elapsedMs", ms);
        s.put("state", state);
        s.put("ts", System.currentTimeMillis());
        stages.add(s);
        if (listener != null) {
            try {
                listener.onProgress(s);
            } catch (Exception ignored) {
            }
        }
    }

    public String resolveUiUrl() {
        String ui = lhProperties.getTrino().getUiUrl();
        if (StrUtil.isNotBlank(ui)) {
            return StrUtil.removeSuffix(ui, "/");
        }
        String base = lhProperties.getTrino().getUrl();
        return StrUtil.isBlank(base) ? null : StrUtil.removeSuffix(base, "/");
    }

    private void trustAll(HttpRequest req) {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            req.setSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception ignored) {
        }
    }

    private static class StatsSnap {
        long processedBytes;
        long elapsedMs;
        boolean truncated;
        String state;
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(Map<String, Object> stage);
    }

    /** 执行选项 */
    public static class ExecuteOptions {
        public int maxRows = 10000;
        public String catalog = "iceberg";
        public String schema = "default";
        /** 终端用户主体（X-Trino-User）；Basic 仍用 Vault 服务账号 */
        public String trinoUser;
        public String source = "lakehouse-portal";
        public String clientTags;
        public Map<String, String> sessionProps;
        public boolean cancelOnLimit = true;
        public int timeoutMs = 60000;
        /** 扫描硬拒上限（字节）；&gt;0 时写入 Trino session query_max_scan_physical_bytes */
        public long maxScanBytes = 0L;
        public ProgressListener onProgress;
        /** HUMAN：代执行映射主体；JOB：服务账号，默认 */
        public Identity identity = Identity.JOB;

        public enum Identity {
            HUMAN, JOB
        }

        public static ExecuteOptions defaults() {
            return job(10000);
        }

        public static ExecuteOptions adhoc(int maxRows) {
            return job(maxRows);
        }

        /** 作业 / 元数据任务：X-Trino-User 与 Basic 相同，不代执行 */
        public static ExecuteOptions job(int maxRows) {
            ExecuteOptions o = new ExecuteOptions();
            o.identity = Identity.JOB;
            o.maxRows = maxRows > 0 ? maxRows : 1000;
            o.cancelOnLimit = true;
            return o;
        }

        /** 人查：必须是已映射主体，禁止服务账号 */
        public static ExecuteOptions human(String trinoUser, int maxRows) {
            ExecuteOptions o = job(maxRows);
            o.identity = Identity.HUMAN;
            o.trinoUser = trinoUser;
            return o;
        }
    }
}
