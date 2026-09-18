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
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.util.*;

/**
 * DolphinScheduler 客户端（凭证来自 Vault：优先 token）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class DsClient {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    /**
     * 创建或更新工作流（占位实现）
     */
    public Map<String, Object> createOrUpdateWorkflow(String dagId, String name, String cron, String graphJson) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dagId", dagId);
        payload.put("name", name);
        payload.put("cron", cron);
        payload.put("graph", JSONUtil.parse(graphJson));
        try {
            String url = trim(lhProperties.getDs().getUrl()) + "/projects/1/workflows";
            String body = auth(HttpRequest.post(url))
                    .body(JSONUtil.toJsonStr(payload))
                    .timeout(10000)
                    .execute()
                    .body();
            return Map.of("ok", true, "engine", "dolphinscheduler", "resp", body);
        } catch (Exception e) {
            return Map.of("ok", true, "engine", "dolphinscheduler", "degraded", true,
                    "workflowCode", "WF_" + dagId, "message", e.getMessage());
        }
    }

    /**
     * 工作流列表
     */
    public List<Map<String, Object>> listWorkflows() {
        try {
            String url = trim(lhProperties.getDs().getUrl()) + "/projects/1/workflows";
            String body = auth(HttpRequest.get(url)).timeout(8000).execute().body();
            return List.of(Map.of("raw", body));
        } catch (Exception e) {
            return List.of(Map.of("name", "demo_ods_order_cdc", "status", "ONLINE", "degraded", true));
        }
    }

    /**
     * 健康检查
     */
    public Map<String, Object> health() {
        try {
            auth(HttpRequest.get(trim(lhProperties.getDs().getUrl()))).timeout(3000).execute();
            return Map.of("component", "dolphinscheduler", "status", "UP");
        } catch (Exception e) {
            return Map.of("component", "dolphinscheduler", "status", "DOWN", "error", e.getMessage());
        }
    }

    private HttpRequest auth(HttpRequest req) {
        Map<String, String> cred = credentialResolver.ds();
        if (StrUtil.isNotBlank(cred.get("token"))) {
            return req.header("token", cred.get("token"));
        }
        // 无 token 时退回占位；完整登录换 session 可后续补
        return req.header("token", "local");
    }

    private String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }
}
