package vip.xiaonuo.lh.modular.compute.support;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轻量假 Gitea：仅 REST（org/repo/pulls），供集成测用；不实现 Git 协议。
 */
public final class FakeGiteaServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger prSeq = new AtomicInteger(100);
    private final Map<Integer, JSONObject> pulls = new ConcurrentHashMap<>();
    private final List<String> posts = new ArrayList<>();

    private FakeGiteaServer(HttpServer server) {
        this.server = server;
    }

    public static FakeGiteaServer start() throws IOException {
        return start(false);
    }

    /** org GET 返回 404，模拟 admin 用户与 org 同名、无组织的部署。 */
    public static FakeGiteaServer startMissingOrg() throws IOException {
        return start(true);
    }

    private static FakeGiteaServer start(boolean orgMissing) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeGiteaServer fake = new FakeGiteaServer(http);
        fake.orgMissing = orgMissing;
        http.createContext("/", fake::handle);
        http.start();
        return fake;
    }

    private boolean orgMissing;

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void mergePull(int number) {
        JSONObject pr = pulls.get(number);
        if (pr != null) {
            pr.set("state", "closed");
            pr.set("merged", true);
        }
    }

    public JSONObject pull(int number) {
        return pulls.get(number);
    }

    public List<String> postedPaths() {
        synchronized (posts) {
            return List.copyOf(posts);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        synchronized (posts) {
            posts.add(method + " " + path);
        }

        try {
            if ("POST".equals(method) && "/api/v1/orgs".equals(path)) {
                json(ex, 201, "{\"id\":1}");
                return;
            }
            if ("GET".equals(method) && path.matches("/api/v1/orgs/[^/]+")) {
                if (orgMissing) {
                    json(ex, 404, "{\"message\":\"not found\"}");
                } else {
                    json(ex, 200, "{\"id\":1,\"username\":\"lakehouse\"}");
                }
                return;
            }
            if ("POST".equals(method) && path.matches("/api/v1/orgs/[^/]+/repos")) {
                json(ex, 201, "{\"id\":1}");
                return;
            }
            if ("POST".equals(method) && "/api/v1/user/repos".equals(path)) {
                json(ex, 201, "{\"id\":1}");
                return;
            }
            if ("PATCH".equals(method) && path.matches("/api/v1/repos/[^/]+/[^/]+")) {
                json(ex, 200, "{}");
                return;
            }
            if ("GET".equals(method) && path.matches("/api/v1/repos/[^/]+/[^/]+")
                    && !path.contains("/pulls")) {
                json(ex, 200, "{\"id\":1,\"name\":\"ws-default\"}");
                return;
            }
            if ("POST".equals(method) && path.matches("/api/v1/repos/[^/]+/[^/]+/pulls")) {
                JSONObject req = JSONUtil.parseObj(body);
                int num = prSeq.incrementAndGet();
                JSONObject pr = new JSONObject();
                pr.set("number", num);
                pr.set("state", "open");
                pr.set("merged", false);
                pr.set("html_url", baseUrl() + path + "/" + num);
                pr.set("title", req.getStr("title"));
                JSONObject head = new JSONObject();
                head.set("ref", req.getStr("head"));
                pr.set("head", head);
                JSONObject base = new JSONObject();
                base.set("ref", req.getStr("base"));
                pr.set("base", base);
                pulls.put(num, pr);
                json(ex, 201, pr.toString());
                return;
            }
            if ("GET".equals(method) && path.matches("/api/v1/repos/[^/]+/[^/]+/pulls/\\d+")) {
                int num = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                JSONObject pr = pulls.get(num);
                if (pr == null) {
                    json(ex, 404, "{\"message\":\"not found\"}");
                    return;
                }
                json(ex, 200, pr.toString());
                return;
            }
            if ("GET".equals(method) && path.matches("/api/v1/repos/[^/]+/[^/]+/pulls")) {
                json(ex, 200, JSONUtil.toJsonStr(pulls.values()));
                return;
            }
            json(ex, 200, "{}");
        } finally {
            ex.close();
        }
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
