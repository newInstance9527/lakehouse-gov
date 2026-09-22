package vip.xiaonuo.lh.modular.export.job;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.export.service.ExportLifecycleService;

import java.util.Map;

/**
 * 出湖到期自动停作业（A6 / J1）。
 * <p>打开：{@code lh.export.expire-enabled=true}（默认开）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "lh.export", name = "expire-enabled", havingValue = "true", matchIfMissing = true)
public class ExportExpireJob {

    @Resource
    private ExportLifecycleService exportLifecycleService;

    @Scheduled(fixedDelayString = "${lh.export.expire-ms:300000}", initialDelay = 90_000)
    public void expireDueExports() {
        try {
            Map<String, Object> r = exportLifecycleService.expireDue();
            int n = r.get("expiredTickets") instanceof Number num ? num.intValue() : 0;
            if (n > 0) {
                log.info("export expire-due: expiredTickets={} pausedDags={} auditLines={}",
                        r.get("expiredTickets"), r.get("pausedDags"), r.get("auditLines"));
            }
        } catch (Exception e) {
            log.warn("export expire job soft-fail: {}", e.getMessage());
        }
    }
}
