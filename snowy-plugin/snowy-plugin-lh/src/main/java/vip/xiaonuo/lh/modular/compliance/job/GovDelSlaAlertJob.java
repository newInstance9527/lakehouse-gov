package vip.xiaonuo.lh.modular.compliance.job;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.compliance.service.GovDelService;

/**
 * 合规 SLA 黄/红告警：剩余 ≤1/3 → 黄（P1）；超期 → 红（P0）推夜莺。
 */
@Slf4j
@Component
public class GovDelSlaAlertJob {

    @Resource
    private GovDelService govDelService;

    @Scheduled(fixedDelayString = "${lh.compliance.sla-alert-ms:900000}", initialDelay = 120_000)
    public void scanSla() {
        try {
            var out = govDelService.scanSlaAlerts(null);
            int warn = out.get("warnPushed") instanceof Number n ? n.intValue() : 0;
            int overdue = out.get("overduePushed") instanceof Number n ? n.intValue() : 0;
            if (warn + overdue > 0) {
                log.info("compliance sla alert warn={} overdue={}", warn, overdue);
            }
        } catch (Exception e) {
            log.warn("compliance sla alert job soft-fail: {}", e.getMessage());
        }
    }
}
