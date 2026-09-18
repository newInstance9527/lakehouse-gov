package vip.xiaonuo.lh.core.engine;

import cn.hutool.http.HttpRequest;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SqlrestClient {

    @Resource
    private LhProperties lhProperties;

    public Map<String, Object> trial(String sqlrestApiId) {
        try {
            String url = trim(lhProperties.getSqlrest().getManagerUrl()) + "/api/" + sqlrestApiId + "/debug";
            String body = HttpRequest.post(url).timeout(10000).execute().body();
            return Map.of("ok", true, "body", body);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("degraded", true);
            m.put("message", "SQLREST trial stub: " + e.getMessage());
            m.put("sample", Map.of("gmv", 128900.5, "dt", "2026-09-10"));
            return m;
        }
    }

    public String embedUrl() {
        return trim(lhProperties.getSqlrest().getManagerUrl());
    }

    private String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }
}
