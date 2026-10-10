package vip.xiaonuo.lh.modular.knowledge.job;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.knowledge.service.GovKbService;

import java.util.Map;

/**
 * 项目纪要记忆老化巡检（周跑）。
 * <p>打开：{@code lh.ai.memory-patrol-enabled=true}</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "lh.ai", name = "memory-patrol-enabled", havingValue = "true")
public class GovKbMemoryPatrolJob {

    @Resource
    private GovKbService govKbService;

    @Scheduled(cron = "${lh.ai.memory-patrol-cron:0 30 3 ? * MON}")
    public void patrol() {
        try {
            Map<String, Object> r = govKbService.memoryPatrol();
            log.info("kb memory patrol done: checked={} archived={} purged={} protected={}",
                    r.get("checked"), r.get("archived"), r.get("purged"), r.get("protected"));
        } catch (Exception e) {
            log.warn("kb memory patrol soft-fail: {}", e.getMessage());
        }
    }
}
