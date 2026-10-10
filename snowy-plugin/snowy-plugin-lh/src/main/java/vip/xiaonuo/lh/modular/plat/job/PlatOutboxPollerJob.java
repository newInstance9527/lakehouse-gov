package vip.xiaonuo.lh.modular.plat.job;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.plat.service.PlatOutboxService;

import java.util.Map;

/**
 * 平台发件箱投递：pending/可重试 failed → handler → sent|failed|dead。
 */
@Slf4j
@Component
public class PlatOutboxPollerJob {

    @Resource
    private PlatOutboxService platOutboxService;
    @Resource
    private LhProperties lhProperties;

    @Scheduled(fixedDelayString = "${lh.outbox.poll-ms:15000}", initialDelay = 45_000)
    public void poll() {
        LhProperties.Outbox cfg = lhProperties.getOutbox();
        if (cfg != null && !cfg.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> r = platOutboxService.pollOnce();
            int processed = r.get("processed") instanceof Number n ? n.intValue() : 0;
            if (processed > 0) {
                log.info("plat_outbox poll processed={} sent={} failed={} dead={}",
                        processed, r.get("sent"), r.get("failed"), r.get("dead"));
            }
        } catch (Exception e) {
            log.warn("plat_outbox poll soft-fail: {}", e.getMessage());
        }
    }
}
