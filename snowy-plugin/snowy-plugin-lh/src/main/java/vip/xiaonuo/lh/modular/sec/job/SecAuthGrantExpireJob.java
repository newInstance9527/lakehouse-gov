package vip.xiaonuo.lh.modular.sec.job;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

/**
 * 将 expires_at 已过的 active grant 标为 expired（鉴权侧本已按时间过滤；本任务做状态回收与审计对齐）。
 */
@Slf4j
@Component
public class SecAuthGrantExpireJob {

    @Resource
    private SecAuthGrantService secAuthGrantService;

    /** 每 5 分钟扫描一次到期授权 */
    @Scheduled(fixedDelayString = "${lh.sec.grant-expire-ms:300000}", initialDelay = 60_000)
    public void expireDueGrants() {
        try {
            int n = secAuthGrantService.expireDueGrants();
            if (n > 0) {
                log.info("sec_auth_grant expired count={}", n);
            }
        } catch (Exception e) {
            log.warn("sec_auth_grant expire job soft-fail: {}", e.getMessage());
        }
    }
}
