package vip.xiaonuo.lh.modular.metric.job;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.metric.support.GovMetricSampleCollector;

/**
 * 指标日波动采样。默认关闭；打开：{@code lh.metric.sample-daily-enabled=true}。
 */
@Component
public class GovMetricSampleJob {

    private static final Logger log = LoggerFactory.getLogger(GovMetricSampleJob.class);

    @Resource
    private LhProperties lhProperties;
    @Resource
    private GovMetricSampleCollector sampleCollector;

    @Scheduled(cron = "${lh.metric.sample-daily-cron:0 15 4 * * ?}")
    public void sampleDaily() {
        LhProperties.Metric m = lhProperties.getMetric();
        if (m == null || !m.isSampleDailyEnabled()) {
            return;
        }
        try {
            var out = sampleCollector.runDaily(null);
            log.info("job.metric.sample_daily ok={} failed={} anomaly={}",
                    out.get("ok"), out.get("failed"), out.get("anomalyCount"));
        } catch (Exception e) {
            log.warn("job.metric.sample_daily failed: {}", e.getMessage());
        }
    }
}
