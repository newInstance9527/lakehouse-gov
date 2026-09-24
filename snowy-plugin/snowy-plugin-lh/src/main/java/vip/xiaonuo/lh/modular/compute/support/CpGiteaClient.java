package vip.xiaonuo.lh.modular.compute.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gitea API：确保组织/仓库，拼装带 token 的 HTTP remote；开 PR / 查合并态。
 * <p>
 * HTTP 使用 JDK {@link HttpClient}（原生支持 PATCH），避免 Hutool HttpURLConnection
 * 反射改 method 在 JDK 17+ 触发 {@code opens java.net} 失败。
 */
@Slf4j
@Component
public class CpGiteaClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final LhProperties lhProperties;

    public CpGiteaClient(LhProperties lhProperties) {
        this.lhProperties = lhProperties;
    }

    public boolean enabled() {
        LhProperties.Gitea g = gitea();
        return g != null && g.isEnabled()
                && StrUtil.isNotBlank(g.getBaseUrl())
                && StrUtil.isNotBlank(g.getToken());
    }

    /**
     * 工作空间对应仓库名。
     * 默认：每空间独立仓 {@code ws-{code}}（{@code default} → {@code ws-default}）。
     * 仅当 {@code lh.compute.gitea.default-repo} 非空时强制共用该仓（运维逃生口）。
     * <p>
     * 消毒：剥 {@code ws_/ws-} 前缀、非 [A-Za-z0-9._-] 换为连字符、折叠多连字符、去首尾连字符；
     * 结果为空时用 {@code fallbackId}（如空间雪花 id），仍空则抛 {@link IllegalArgumentException}
     *（禁止产出 {@code ws--} / {@code ws-}）。
     */
    public String repoNameFor(String wsCode) {
        return repoNameFor(wsCode, null);
    }

    /**
     * @param fallbackId 编码消毒后为空时的稳定后缀（建议空间主键）；勿传随机值
     */
    public String repoNameFor(String wsCode, String fallbackId) {
        LhProperties.Gitea g = gitea();
        if (g != null && StrUtil.isNotBlank(g.getDefaultRepo())) {
            return g.getDefaultRepo().trim();
        }
        // 空编码历史约定映射 default → ws-default
        String body = sanitizeRepoBody(StrUtil.blankToDefault(wsCode, "default"));
        if (StrUtil.isBlank(body)) {
            body = sanitizeRepoBody(fallbackId);
        }
        if (StrUtil.isBlank(body)) {
            throw new IllegalArgumentException(
                    "工作空间编码无效，无法生成 Gitea 仓库名（需含字母或数字）: " + wsCode);
        }
        return "ws-" + body;
    }

    /**
     * 将空间编码消毒为仓库名后缀（无 {@code ws-} 前缀）。空串表示不可用。
     */
    static String sanitizeRepoBody(String wsCode) {
        if (StrUtil.isBlank(wsCode)) {
            return "";
        }
        String body = wsCode.trim().toLowerCase(java.util.Locale.ROOT);
        if (body.startsWith("ws-") || body.startsWith("ws_")) {
            body = body.substring(3);
        }
        body = body.replace('_', '-')
                .replaceAll("[^a-z0-9.\\-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        return body;
    }

    /** 已落库的 remote 是否指向畸形仓名（如 {@code ws--.git} / {@code ws-.git}）。 */
    public static boolean isMalformedWsRepoRemote(String remoteUrl) {
        if (StrUtil.isBlank(remoteUrl)) {
            return false;
        }
        String path = remoteUrl.trim().toLowerCase(java.util.Locale.ROOT);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return "ws-".equals(name) || "ws--".equals(name)
                || name.matches("ws-+")
                || (name.startsWith("ws-") && name.contains("--"));
    }

    /**
     * 带凭证的 clone/push URL；未启用返回 null。
     */
    public String remoteUrlFor(String wsCode) {
        return remoteUrlFor(wsCode, null);
    }

    public String remoteUrlFor(String wsCode, String fallbackId) {
        if (!enabled()) {
            return null;
        }
        LhProperties.Gitea g = gitea();
        String host = stripSlash(g.getBaseUrl()).replaceFirst("^https?://", "");
        String scheme = g.getBaseUrl().trim().toLowerCase().startsWith("https") ? "https" : "http";
        String org = StrUtil.blankToDefault(g.getOrg(), "lakehouse");
        String repo = repoNameFor(wsCode, fallbackId);
        String user = StrUtil.blankToDefault(g.getUsername(), "lakehouse");
        return scheme + "://" + user + ":" + g.getToken() + "@" + host + "/" + org + "/" + repo + ".git";
    }

    /** 公开浏览 URL（不含 token）。 */
    public String browseUrl(String wsCode) {
        return browseUrl(wsCode, null);
    }

    public String browseUrl(String wsCode, String fallbackId) {
        if (!enabled()) {
            return null;
        }
        LhProperties.Gitea g = gitea();
        return stripSlash(g.getBaseUrl()) + "/" + StrUtil.blankToDefault(g.getOrg(), "lakehouse")
                + "/" + repoNameFor(wsCode, fallbackId);
    }

    /**
     * 确保 org + repo 存在（幂等）。
     * @return null 成功；否则可读错误（含不可达），调用方决定硬失败或 soft。
     */
    public String ensureRepo(String wsCode) {
        return ensureRepo(wsCode, null);
    }

    public String ensureRepo(String wsCode, String fallbackId) {
        if (!enabled()) {
            return "gitea disabled";
        }
        final String repo;
        try {
            repo = repoNameFor(wsCode, fallbackId);
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
        LhProperties.Gitea g = gitea();
        String base = stripSlash(g.getBaseUrl());
        String org = StrUtil.blankToDefault(g.getOrg(), "lakehouse");
        String token = g.getToken();
        try {
            // owner：优先组织；与 username 同名时 Gitea 无法再建同名 org（422），改走用户命名空间
            boolean userOwner = resolveUserOwner(base, org, g.getUsername(), token);
            if (!userOwner) {
                api("POST", base + "/api/v1/orgs", token,
                        JSONUtil.toJsonStr(new JSONObject()
                                .set("username", org)
                                .set("visibility", "private")
                                .set("description", "lakehouse scripts SoT")),
                        15_000);
            }
            if (g.isAutoCreateRepo()) {
                String createBody = JSONUtil.toJsonStr(new JSONObject()
                        .set("name", repo)
                        .set("private", true)
                        .set("auto_init", true)
                        .set("default_branch", "stg")
                        .set("description", "workspace " + StrUtil.blankToDefault(wsCode, repo)));
                if (userOwner) {
                    // POST /user/repos → owner=token 用户，路径仍为 {username}/{repo}
                    api("POST", base + "/api/v1/user/repos", token, createBody, 15_000);
                } else {
                    ApiResult created = api("POST", base + "/api/v1/orgs/" + org + "/repos",
                            token, createBody, 15_000);
                    // org 不存在时回退用户仓（与 username 同名的历史部署）
                    if (created.status() == 404 && StrUtil.equalsIgnoreCase(org, g.getUsername())) {
                        log.info("Gitea org {} missing, create repo under user namespace", org);
                        api("POST", base + "/api/v1/user/repos", token, createBody, 15_000);
                    }
                }
                // soft：default_branch=stg（JDK HttpClient 原生 PATCH，无反射）
                api("PATCH", base + "/api/v1/repos/" + org + "/" + repo, token,
                        "{\"default_branch\":\"stg\"}",
                        10_000);
            }
            String verify = verifyRepo(base, org, repo, token);
            if (verify != null) {
                return verify;
            }
            return null;
        } catch (Exception e) {
            log.warn("ensure Gitea repo {}/{} fail: {}", org, repo, e.getMessage());
            return "Gitea 不可达或确保仓库失败（" + base + "）: " + e.getMessage();
        }
    }

    /**
     * 判定 remote owner 是否为「用户」而非组织。
     * 典型场景：admin 用户名与配置 org 相同（如均为 lakehouse），Gitea 不能再建同名 org。
     */
    private static boolean resolveUserOwner(String base, String org, String username, String token)
            throws Exception {
        if (StrUtil.isNotBlank(username) && StrUtil.equalsIgnoreCase(org, username)) {
            ApiResult orgGet = api("GET", base + "/api/v1/orgs/" + org, token, null, 10_000);
            if (orgGet.status() == 404) {
                return true;
            }
        }
        return false;
    }

    /** GET 校验仓库存在；失败返回错误文案。 */
    private String verifyRepo(String base, String org, String repo, String token) {
        try {
            ApiResult r = api("GET", base + "/api/v1/repos/" + org + "/" + repo, token, null, 10_000);
            int st = r.status();
            if (st >= 200 && st < 300) {
                return null;
            }
            if (st == 401 || st == 403) {
                return "Gitea 鉴权失败 HTTP " + st + "（检查 lh.compute.gitea.token）";
            }
            if (st == 404) {
                return "Gitea 仓库不存在 " + org + "/" + repo
                        + "（可开 auto-create-repo 或先 bootstrap）";
            }
            return "Gitea 校验仓库失败 HTTP " + st + ": "
                    + StrUtil.maxLength(StrUtil.blankToDefault(r.body(), ""), 160);
        } catch (Exception e) {
            return "Gitea 不可达（" + base + "）: " + e.getMessage();
        }
    }

    /**
     * 已落库的 remote 是否像内网主机（外网客户端不可达）。
     * 用于自动改写为当前 {@code lh.compute.gitea.base-url}。
     */
    public static boolean looksLikeIntranetRemote(String remoteUrl) {
        if (StrUtil.isBlank(remoteUrl)) {
            return false;
        }
        String u = remoteUrl.trim().toLowerCase();
        // 剥 scheme / userinfo，取 host
        String host = u;
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        } else {
            host = host.replaceFirst("^https?://", "");
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (host.startsWith("10.")) {
            return true;
        }
        if (host.startsWith("192.168.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 展示用脱敏：去掉 userinfo（永不返回 {@code user:token@}）。
     */
    public static String redactRemoteUrl(String remoteUrl) {
        if (StrUtil.isBlank(remoteUrl)) {
            return null;
        }
        String u = remoteUrl.trim();
        // scheme://user:pass@host → scheme://host ；亦覆盖 user@host
        return u.replaceFirst("://[^/@]+(?::[^/@]*)?@", "://");
    }

    /** 配置的 Gitea host（小写，无 scheme/port 外的 path）。 */
    public String configuredHost() {
        if (!enabled()) {
            return null;
        }
        return hostOf(stripSlash(gitea().getBaseUrl()));
    }

    /**
     * 是否平台托管的 Gitea remote（同配置 host，含历史共用 ws-default）。
     * 托管地址在同步时改写为当前空间独立仓；自定义公网（他站）保留。
     */
    public boolean isPlatformManagedRemote(String remoteUrl) {
        if (StrUtil.isBlank(remoteUrl) || !enabled()) {
            return false;
        }
        String conf = configuredHost();
        String host = hostOf(remoteUrl.trim());
        return StrUtil.isNotBlank(conf) && conf.equalsIgnoreCase(host);
    }

    /**
     * 自定义公网 remote：非空、非内网、且非本平台 Gitea host → 同步/推送解析时保留。
     */
    public boolean isCustomPublicRemote(String remoteUrl) {
        if (StrUtil.isBlank(remoteUrl)) {
            return false;
        }
        if (looksLikeIntranetRemote(remoteUrl)) {
            return false;
        }
        return !isPlatformManagedRemote(remoteUrl);
    }

    /**
     * 空 / 内网 / 平台托管 → 应改写为当前公网 per-ws remote；自定义公网 → 否。
     */
    public boolean shouldRewriteRemote(String remoteUrl) {
        if (StrUtil.isBlank(remoteUrl)) {
            return true;
        }
        if (looksLikeIntranetRemote(remoteUrl)) {
            return true;
        }
        return isPlatformManagedRemote(remoteUrl);
    }

    /** 从 URL 取 host（小写，无 port）。 */
    public static String hostOf(String url) {
        if (StrUtil.isBlank(url)) {
            return "";
        }
        String u = url.trim().toLowerCase();
        int at = u.lastIndexOf('@');
        if (at >= 0) {
            u = u.substring(at + 1);
        } else {
            u = u.replaceFirst("^https?://", "");
        }
        int slash = u.indexOf('/');
        if (slash >= 0) {
            u = u.substring(0, slash);
        }
        int colon = u.indexOf(':');
        if (colon >= 0) {
            u = u.substring(0, colon);
        }
        return u;
    }

    /**
     * 开 PR：head → base。返回 number / htmlUrl / state；失败返回 error 字段，不抛。
     */
    public Map<String, Object> openPullRequest(String wsCode, String head, String base,
                                               String title, String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled()) {
            out.put("error", "gitea disabled");
            return out;
        }
        LhProperties.Gitea g = gitea();
        String org = StrUtil.blankToDefault(g.getOrg(), "lakehouse");
        String repo = repoNameFor(wsCode);
        String url = stripSlash(g.getBaseUrl()) + "/api/v1/repos/" + org + "/" + repo + "/pulls";
        JSONObject payload = new JSONObject()
                .set("title", StrUtil.blankToDefault(title, "release"))
                .set("body", StrUtil.blankToDefault(body, ""))
                .set("head", head)
                .set("base", StrUtil.blankToDefault(base, "prod"));
        try {
            ApiResult r = api("POST", url, g.getToken(), payload.toString(), 20_000);
            String text = r.body();
            if (r.status() >= 200 && r.status() < 300) {
                return parsePr(text, out);
            }
            // 已存在同 head/base 时 Gitea 常 409/422 —— 尝试按 head 查找
            Map<String, Object> existing = findOpenPrByHead(wsCode, head, base);
            if (existing.get("number") != null) {
                return existing;
            }
            out.put("error", "HTTP " + r.status() + ": " + StrUtil.maxLength(text, 240));
            return out;
        } catch (Exception e) {
            out.put("error", e.getMessage());
            return out;
        }
    }

    /**
     * 合并已打开的 PR（申请通过后由门户触发，避免「审批过了但无人合并」）。
     */
    public Map<String, Object> mergePullRequest(String wsCode, int number, String mergeMessage) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled() || number <= 0) {
            out.put("error", number <= 0 ? "invalid pr number" : "gitea disabled");
            out.put("ok", false);
            return out;
        }
        LhProperties.Gitea g = gitea();
        String org = StrUtil.blankToDefault(g.getOrg(), "lakehouse");
        String repo = repoNameFor(wsCode);
        String url = stripSlash(g.getBaseUrl()) + "/api/v1/repos/" + org + "/" + repo + "/pulls/" + number + "/merge";
        JSONObject payload = new JSONObject()
                .set("Do", "merge")
                .set("MergeMessageField", StrUtil.blankToDefault(mergeMessage, "merge release PR #" + number));
        try {
            ApiResult r = api("POST", url, g.getToken(), payload.toString(), 30_000);
            String text = r.body();
            if (r.status() >= 200 && r.status() < 300) {
                out.put("ok", true);
                // 再查一次状态
                Map<String, Object> pr = getPullRequest(wsCode, number);
                out.putAll(pr);
                if (!"merged".equalsIgnoreCase(String.valueOf(pr.get("state")))) {
                    out.put("state", "merged");
                    out.put("merged", true);
                }
                return out;
            }
            // 可能已经合并
            Map<String, Object> pr = getPullRequest(wsCode, number);
            if ("merged".equalsIgnoreCase(String.valueOf(pr.get("state")))
                    || Boolean.TRUE.equals(pr.get("merged"))) {
                out.put("ok", true);
                out.putAll(pr);
                return out;
            }
            out.put("ok", false);
            out.put("error", "HTTP " + r.status() + ": " + StrUtil.maxLength(text, 240));
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            return out;
        }
    }

    /** 查 PR 状态：state / merged / htmlUrl。 */
    public Map<String, Object> getPullRequest(String wsCode, int number) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled() || number <= 0) {
            out.put("error", number <= 0 ? "invalid pr number" : "gitea disabled");
            return out;
        }
        LhProperties.Gitea g = gitea();
        String org = StrUtil.blankToDefault(g.getOrg(), "lakehouse");
        String repo = repoNameFor(wsCode);
        String url = stripSlash(g.getBaseUrl()) + "/api/v1/repos/" + org + "/" + repo + "/pulls/" + number;
        try {
            ApiResult r = api("GET", url, g.getToken(), null, 15_000);
            if (r.status() >= 200 && r.status() < 300) {
                return parsePr(r.body(), out);
            }
            out.put("error", "HTTP " + r.status());
            return out;
        } catch (Exception e) {
            out.put("error", e.getMessage());
            return out;
        }
    }

    private Map<String, Object> findOpenPrByHead(String wsCode, String head, String base) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled()) {
            return out;
        }
        LhProperties.Gitea g = gitea();
        String org = StrUtil.blankToDefault(g.getOrg(), "lakehouse");
        String repo = repoNameFor(wsCode);
        String url = stripSlash(g.getBaseUrl()) + "/api/v1/repos/" + org + "/" + repo
                + "/pulls?state=open&limit=50";
        try {
            ApiResult r = api("GET", url, g.getToken(), null, 15_000);
            if (r.status() < 200 || r.status() >= 300) {
                return out;
            }
            for (Object o : JSONUtil.parseArray(r.body())) {
                JSONObject jo = JSONUtil.parseObj(o);
                String h = jo.getByPath("head.ref", String.class);
                String b = jo.getByPath("base.ref", String.class);
                if (StrUtil.equals(head, h) && (StrUtil.isBlank(base) || StrUtil.equals(base, b))) {
                    return parsePr(jo.toString(), out);
                }
            }
        } catch (Exception e) {
            log.debug("findOpenPr soft: {}", e.getMessage());
        }
        return out;
    }

    private static Map<String, Object> parsePr(String json, Map<String, Object> out) {
        JSONObject jo = JSONUtil.parseObj(json);
        Integer num = jo.getInt("number");
        if (num != null) {
            out.put("number", num);
        }
        out.put("htmlUrl", StrUtil.blankToDefault(jo.getStr("html_url"), jo.getStr("url")));
        String state = jo.getStr("state");
        boolean merged = Boolean.TRUE.equals(jo.getBool("merged"));
        if (merged) {
            state = "merged";
        }
        out.put("state", state);
        out.put("merged", merged);
        return out;
    }

    /**
     * JDK HttpClient 调用；{@code jsonBody} 为空则无请求体。
     * PATCH/PUT 走 {@link HttpRequest.Builder#method}，无需反射改 HttpURLConnection。
     */
    private static ApiResult api(String method, String url, String token, String jsonBody, int timeoutMs)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "token " + token);
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ApiResult(r.statusCode(), r.body());
    }

    private record ApiResult(int status, String body) {
    }

    private LhProperties.Gitea gitea() {
        return lhProperties.getCompute() == null ? null : lhProperties.getCompute().getGitea();
    }

    private static String stripSlash(String url) {
        String u = StrUtil.blankToDefault(url, "").trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
