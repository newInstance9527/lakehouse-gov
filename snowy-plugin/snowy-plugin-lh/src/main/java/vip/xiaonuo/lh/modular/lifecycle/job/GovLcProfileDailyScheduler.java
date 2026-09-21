package vip.xiaonuo.lh.modular.lifecycle.job;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcStorageProfileCollector;

/**
 * 存储画像日批。默认关闭，避免无 Trino 的环境每天打空。
 * 打开：{@code lh.lifecycle.profile-daily-enabled=true}（cron 默认每天 03:30）。
 */
@Component
public class GovLcProfileDailyScheduler {

    private static final Logger log = LoggerFactory.getLogger(GovLcProfileDailyScheduler.class);

    @Resource
    private LhProperties lhProperties;
    @Resource
    private GovLcStorageProfileCollector profileCollector;

    @Scheduled(cron = "${lh.lifecycle.profile-daily-cron:0 30 3 * * ?}")
    public void profileDaily() {
        if (lhProperties.getLifecycle() == null || !lhProperties.getLifecycle().isProfileDailyEnabled()) {
            return;
        }
        try {
            profileCollector.runDaily(null);
        } catch (Exception e) {
            log.warn("job.storage.profile_daily failed: {}", e.getMessage());
        }
    }
}
