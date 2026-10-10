package vip.xiaonuo.lh.modular.plat.support;

import vip.xiaonuo.lh.modular.plat.entity.PlatOutboxEvent;

/**
 * 平台发件箱消费者：按 {@code event_type} 扩展；副作用须幂等。
 */
public interface PlatOutboxHandler {

    /** 是否处理该事件类型（可前缀匹配）。 */
    boolean supports(String eventType);

    /**
     * 处理一条事件；抛异常则由投递器记 failed/dead 并退避重试。
     */
    void handle(PlatOutboxEvent event) throws Exception;

    /** 同类型多 handler 时优先级，数值越小越先匹配。 */
    default int order() {
        return 100;
    }
}
