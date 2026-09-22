package vip.xiaonuo.lh.modular.catalog.job;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.catalog.support.GovAssetMetaDriftReconcile;

/**
 * 元数据漂移日批（默认关；打开：{@code lh.catalog.meta-drift-enabled=true}）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "lh.catalog", name = "meta-drift-enabled", havingValue = "true")
public class GovAssetMetaDriftScheduler {

    @Resource
    private GovAssetMetaDriftReconcile metaDriftReconcile;

    @Scheduled(cron = "${lh.catalog.meta-drift-cron:0 15 4 * * ?}")
    public void runDaily() {
        try {
            var r = metaDriftReconcile.reconcileWorkspace(null);
            log.info("catalog meta-drift daily scanned={} opened={} delisted={}",
                    r.get("scanned"), r.get("opened"), r.get("goldDelisted"));
        } catch (Exception e) {
            log.warn("catalog meta-drift daily failed: {}", e.getMessage());
        }
    }
}
