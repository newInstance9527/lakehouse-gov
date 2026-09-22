package vip.xiaonuo.lh.modular.aimodel.job;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.aimodel.service.GovAiModelService;

import java.util.Map;

/**
 * AI 模型连通 / Vault Key 巡检（D1）。
 * <p>打开：{@code lh.ai.patrol-enabled=true}；依赖 {@code lh.ai.litellm-url} 做连通探测。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "lh.ai", name = "patrol-enabled", havingValue = "true")
public class GovAiModelPatrolJob {

    @Resource
    private GovAiModelService govAiModelService;

    @Scheduled(cron = "${lh.ai.patrol-cron:0 0 * * * ?}")
    public void patrol() {
        try {
            Map<String, Object> r = govAiModelService.patrol(null);
            log.info("ai model patrol done: checked={} warn={} fail={} litellmOk={}",
                    r.get("checked"), r.get("warnCount"), r.get("failCount"), r.get("litellmOk"));
        } catch (Exception e) {
            log.warn("ai model patrol soft-fail: {}", e.getMessage());
        }
    }
}
