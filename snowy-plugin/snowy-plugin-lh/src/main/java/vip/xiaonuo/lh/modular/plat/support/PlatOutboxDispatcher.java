package vip.xiaonuo.lh.modular.plat.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.plat.entity.PlatOutboxEvent;

import java.util.Comparator;
import java.util.List;

/**
 * 按 order 选择首个 {@link PlatOutboxHandler#supports(String)} 的 handler。
 */
@Component
public class PlatOutboxDispatcher {

    @Resource
    private List<PlatOutboxHandler> handlers;

    public PlatOutboxHandler resolve(String eventType) {
        if (handlers == null || handlers.isEmpty()) {
            throw new CommonException("无 plat_outbox handler 注册");
        }
        String type = StrUtil.blankToDefault(eventType, "");
        return handlers.stream()
                .sorted(Comparator.comparingInt(PlatOutboxHandler::order))
                .filter(h -> h.supports(type))
                .findFirst()
                .orElseThrow(() -> new CommonException("无 handler 支持 event_type=" + type));
    }

    public void dispatch(PlatOutboxEvent event) throws Exception {
        if (event == null) {
            throw new CommonException("outbox event 为空");
        }
        resolve(event.getEventType()).handle(event);
    }
}
