package vip.xiaonuo.lh.modular.plat.support;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.plat.entity.PlatOutboxEvent;

/**
 * 默认排水：现有 outbox 多为同步副作用后的审计事件，确认落库后标 sent。
 * <p>具体跨系统补偿 handler 可另注册并以更小 {@link #order()} 抢先匹配。</p>
 */
@Slf4j
@Component
public class PlatOutboxAuditDrainHandler implements PlatOutboxHandler {

    @Override
    public boolean supports(String eventType) {
        return StrUtil.isNotBlank(eventType);
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public void handle(PlatOutboxEvent event) {
        log.info("plat_outbox drain type={} eventId={} agg={}.{}",
                event.getEventType(),
                event.getEventId(),
                event.getAggregateType(),
                event.getAggregateId());
    }
}
