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

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.vault.LhComponentCredentialResolver;

import java.util.List;
import java.util.Map;

/**
 * Flink 客户端（Basic Auth 来自 Vault）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class FlinkClient {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private LhComponentCredentialResolver credentialResolver;

    /**
     * 作业概览
     */
    public List<Map<String, Object>> listJobs() {
        try {
            Map<String, String> cred = credentialResolver.flink();
            String url = trim(lhProperties.getFlink().getUrl()) + "/jobs/overview";
            String body = HttpRequest.get(url)
                    .basicAuth(cred.get("username"), cred.get("password"))
                    .timeout(8000).execute().body();
            return List.of(Map.of("raw", JSONUtil.parseObj(body)));
        } catch (Exception e) {
            return List.of(Map.of("jid", "demo-cdc-order", "name", "cdc_s_order", "state", "RUNNING", "degraded", true));
        }
    }

    /**
     * 健康检查
     */
    public Map<String, Object> health() {
        try {
            Map<String, String> cred = credentialResolver.flink();
            HttpRequest.get(trim(lhProperties.getFlink().getUrl()) + "/overview")
                    .basicAuth(cred.get("username"), cred.get("password"))
                    .timeout(3000).execute();
            return Map.of("component", "flink", "status", "UP");
        } catch (Exception e) {
            return Map.of("component", "flink", "status", "DOWN", "error", e.getMessage());
        }
    }

    private String trim(String url) {
        return url == null ? "" : (url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }
}
