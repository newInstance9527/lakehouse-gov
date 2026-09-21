package vip.xiaonuo.lh.modular.sec.job;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.sec.service.GravTableAccessService;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

/**
 * 将 expires_at 已过的 active grant 标为 expired（鉴权侧本已按时间过滤；本任务做状态回收与审计对齐）。
 */
@Slf4j
@Component
public class SecAuthGrantExpireJob {

    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private GravTableAccessService gravTableAccessService;

    /** 每 5 分钟：操作权到期标 expired；表读先在 Gravitino 回收，成功后再标 SELECT 投影过期 */
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
        try {
            int revoked = gravTableAccessService.revokeExpired();
            if (revoked > 0) {
                log.info("gravitino table grant revoked count={}", revoked);
            }
        } catch (Exception e) {
            log.warn("gravitino revoke job soft-fail: {}", e.getMessage());
        }
    }
}
