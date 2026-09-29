package vip.xiaonuo.lh.modular.lifecycle.job;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcStorageInventoryCalibrator;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcStorageReportSubSupport;

/**
 * 存储日报订阅日批 + 可选 inventory 校准。
 */
@Component
public class GovLcStorageReportSubJob {

    private static final Logger log = LoggerFactory.getLogger(GovLcStorageReportSubJob.class);

    @Resource
    private LhProperties lhProperties;
    @Resource
    private GovLcStorageReportSubSupport reportSubSupport;
    @Resource
    private GovLcStorageInventoryCalibrator inventoryCalibrator;

    @Scheduled(cron = "${lh.lifecycle.report-sub-cron:0 0 8 * * ?}")
    public void pushDailyReports() {
        LhProperties.Lifecycle lc = lhProperties.getLifecycle();
        if (lc == null || !lc.isReportSubEnabled()) {
            return;
        }
        try {
            var out = reportSubSupport.runDue();
            log.info("job.storage.report_sub total={} ok={} failed={}",
                    out.get("total"), out.get("ok"), out.get("failed"));
        } catch (Exception e) {
            log.warn("job.storage.report_sub failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${lh.lifecycle.inventory-calibrate-cron:0 30 3 * * SUN}")
    public void calibrateInventoryWeekly() {
        LhProperties.Lifecycle lc = lhProperties.getLifecycle();
        if (lc == null || !lc.isInventoryCalibrateEnabled()) {
            return;
        }
        try {
            var out = inventoryCalibrator.calibrate();
            log.info("job.storage.inventory_calibrate ok={} calibrated={} skipped={}",
                    out.get("ok"), out.get("calibrated"), out.get("skipped"));
        } catch (Exception e) {
            log.warn("job.storage.inventory_calibrate failed: {}", e.getMessage());
        }
    }
}
