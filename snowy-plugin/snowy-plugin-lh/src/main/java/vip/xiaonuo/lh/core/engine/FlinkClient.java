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
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flink 客户端：作业列表 / 提交 / 查询（soft-fail）
 */
@Component
public class FlinkClient {

    private static final Logger log = LoggerFactory.getLogger(FlinkClient.class);

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    public List<Map<String, Object>> listJobs() {
        try {
            Map<String, String> cred = credentialResolver.flink();
            String url = trim(lhProperties.getFlink().getUrl()) + "/jobs/overview";
            String body = HttpRequest.get(url)
                    .basicAuth(cred.get("username"), cred.get("password"))
                    .timeout(8000).execute().body();
            JSONObject jo = JSONUtil.parseObj(body);
            JSONArray jobs = jo.getJSONArray("jobs");
            List<Map<String, Object>> out = new ArrayList<>();
            if (jobs != null) {
                for (Object o : jobs) {
                    if (o instanceof JSONObject j) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("jid", j.getStr("jid"));
                        m.put("name", j.getStr("name"));
                        m.put("state", j.getStr("state"));
                        m.put("startTime", j.get("start-time"));
                        out.add(m);
                    }
                }
            }
            if (out.isEmpty()) {
                out.add(Map.of("raw", jo));
            }
            return out;
        } catch (Exception e) {
            return List.of(Map.of("jid", "demo-cdc-order", "name", "cdc_s_order", "state", "RUNNING", "degraded", true));
        }
    }

    /**
     * 查询单个作业状态
     */
    public Map<String, Object> getJob(String jobId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "flink");
        out.put("jobId", jobId);
        if (StrUtil.isBlank(jobId)) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", "无 jobId");
            return out;
        }
        try {
            Map<String, String> cred = credentialResolver.flink();
            String url = trim(lhProperties.getFlink().getUrl()) + "/jobs/" + jobId;
            String body = HttpRequest.get(url)
                    .basicAuth(cred.get("username"), cred.get("password"))
                    .timeout(8000).execute().body();
            JSONObject jo = JSONUtil.parseObj(body);
            out.put("ok", true);
            out.put("degraded", false);
            out.put("state", jo.getStr("state"));
            out.put("name", jo.getStr("name"));
            out.put("resp", truncate(body, 1500));
            return out;
        } catch (Exception e) {
            log.warn("Flink getJob soft-fail {}: {}", jobId, e.getMessage());
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 提交已上传 JAR（Flink Rest /jars/:jarid/run）
     */
    public Map<String, Object> submitJar(String jarId, String entryClass, String programArgs, Integer parallelism) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "flink");
        out.put("action", "submitJar");
        out.put("jarId", jarId);
        if (StrUtil.isBlank(jarId)) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", "jarId 必填");
            return out;
        }
        String base = trim(lhProperties.getFlink().getUrl());
        if (StrUtil.isBlank(base)) {
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", "lh.flink.url 未配置");
            return out;
        }
        try {
            Map<String, String> cred = credentialResolver.flink();
            Map<String, Object> payload = new LinkedHashMap<>();
            if (StrUtil.isNotBlank(entryClass)) {
                payload.put("entryClass", entryClass);
            }
            if (StrUtil.isNotBlank(programArgs)) {
                payload.put("programArgs", programArgs);
            }
            payload.put("parallelism", parallelism == null ? 1 : parallelism);
            String url = base + "/jars/" + jarId + "/run";
            String body = HttpRequest.post(url)
                    .basicAuth(cred.get("username"), cred.get("password"))
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(payload))
                    .timeout(30000)
                    .execute()
                    .body();
            out.put("ok", true);
            out.put("degraded", false);
            out.put("resp", truncate(body, 1500));
            JSONObject jo = JSONUtil.parseObj(body);
            String jid = firstNonBlank(jo.getStr("jobid"), jo.getStr("jid"), jo.getStr("jobId"));
            if (StrUtil.isNotBlank(jid)) {
                out.put("jobId", jid);
            }
            return out;
        } catch (Exception e) {
            log.warn("Flink submitJar soft-fail jar={}: {}", jarId, e.getMessage());
            out.put("ok", false);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 预览 Worker 侧脚本（不真正提交）
     */
    public Map<String, Object> previewSubmitScript(String nodeKey, String nodeType, String sql, Map<String, Object> conf) {
        vip.xiaonuo.lh.modular.etl.entity.IgEtlNode n = new vip.xiaonuo.lh.modular.etl.entity.IgEtlNode();
        n.setNodeKey(StrUtil.blankToDefault(nodeKey, "preview"));
        n.setNodeType(StrUtil.blankToDefault(nodeType, "transform"));
        JSONObject c = conf == null ? JSONUtil.createObj() : JSONUtil.parseObj(JSONUtil.toJsonStr(conf));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "flink");
        out.put("script", FlinkSubmitBuilder.buildShell(n, StrUtil.blankToDefault(sql, "SELECT 1"), c));
        return out;
    }

    public Map<String, Object> health() {
        try {
            Map<String, String> cred = credentialResolver.flink();
            HttpRequest.get(trim(lhProperties.getFlink().getUrl()) + "/overview")
                    .basicAuth(cred.get("username"), cred.get("password"))
                    .timeout(3000).execute();
            return Map.of("component", "flink", "status", "UP");
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", "flink");
            m.put("status", "DOWN");
            m.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return m;
        }
    }

    private String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
