package vip.xiaonuo.lh.modular.query.support;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * adhoc 并发闸门（全栈 §24：即席并发 ≤ 20）
 */
public final class CpQueryConcurrencyGuard {

    /** 平台即席并发上限 */
    public static final int ADHOC_MAX_CONCURRENT = 20;

    private static final AtomicInteger RUNNING = new AtomicInteger(0);

    private CpQueryConcurrencyGuard() {
    }

    public static int current() {
        return RUNNING.get();
    }

    public static int max() {
        return ADHOC_MAX_CONCURRENT;
    }

    /** @return true 若获得名额 */
    public static boolean tryAcquire() {
        while (true) {
            int c = RUNNING.get();
            if (c >= ADHOC_MAX_CONCURRENT) {
                return false;
            }
            if (RUNNING.compareAndSet(c, c + 1)) {
                return true;
            }
        }
    }

    public static void release() {
        RUNNING.updateAndGet(v -> Math.max(0, v - 1));
    }

    public static String rejectMessage() {
        return "adhoc 队列并发已满（≤" + ADHOC_MAX_CONCURRENT
                + "），请稍后重试或改走看板队列 / 缩小查询范围";
    }
}
