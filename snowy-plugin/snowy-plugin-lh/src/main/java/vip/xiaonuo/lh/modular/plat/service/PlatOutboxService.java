/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.plat.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.plat.entity.PlatOutboxEvent;
import vip.xiaonuo.lh.modular.plat.mapper.PlatOutboxEventMapper;
import vip.xiaonuo.lh.modular.plat.support.PlatOutboxDispatcher;

import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台发件箱写入 + 投递（pending → handler → sent/failed/dead）。
 *
 * @author lakehouse
 * @date 2026/3/19
 */
@Slf4j
@Service
public class PlatOutboxService {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_DEAD = "dead";

    @Resource
    private PlatOutboxEventMapper outboxEventMapper;
    @Resource
    private PlatOutboxDispatcher dispatcher;
    @Resource
    private LhProperties lhProperties;

    /**
     * 追加一条 pending 事件；失败只打日志不抛（调用方业务已成功时 soft-fail 审计）。
     *
     * @return eventId；写入失败返回 null
     */
    public String appendSoft(String eventType, String aggregateType, String aggregateId,
                             Map<String, Object> payload, Map<String, Object> headers) {
        try {
            return append(eventType, aggregateType, aggregateId, payload, headers, null);
        } catch (Exception e) {
            log.warn("plat_outbox soft-fail type={} agg={}.{}: {}",
                    eventType, aggregateType, aggregateId, e.getMessage());
            return null;
        }
    }

    public String append(String eventType, String aggregateType, String aggregateId,
                         Map<String, Object> payload, Map<String, Object> headers, String createUser) {
        String eventId = IdUtil.getSnowflakeNextIdStr();
        PlatOutboxEvent row = new PlatOutboxEvent();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setEventId(eventId);
        row.setEventType(eventType);
        row.setAggregateType(aggregateType);
        row.setAggregateId(StrUtil.blankToDefault(aggregateId, "-"));
        row.setPayload(payload == null ? "{}" : JSONUtil.toJsonStr(payload));
        row.setHeaders(headers == null ? null : JSONUtil.toJsonStr(headers));
        row.setStatus(STATUS_PENDING);
        row.setRetryCount(0);
        row.setCreateTime(new Date());
        row.setCreateUser(createUser);
        outboxEventMapper.insert(row);
        return eventId;
    }

    /**
     * 拉取一批到期事件并投递。返回计数：processed/sent/failed/dead/skipped。
     */
    public Map<String, Object> pollOnce() {
        LhProperties.Outbox cfg = lhProperties.getOutbox() == null
                ? new LhProperties.Outbox() : lhProperties.getOutbox();
        int batch = Math.max(1, Math.min(200, cfg.getBatchSize()));
        int maxRetry = Math.max(1, cfg.getMaxRetry());
        int baseBackoffSec = Math.max(5, cfg.getBackoffSeconds());

        List<PlatOutboxEvent> due = listDue(batch);
        int sent = 0;
        int failed = 0;
        int dead = 0;
        int skipped = 0;
        for (PlatOutboxEvent ev : due) {
            if (ev == null || StrUtil.isBlank(ev.getId())) {
                skipped++;
                continue;
            }
            // 乐观认领：仍为 pending/可重试 failed 才继续
            if (!claim(ev.getId(), ev.getStatus())) {
                skipped++;
                continue;
            }
            try {
                dispatcher.dispatch(ev);
                if (markSent(ev.getId())) {
                    sent++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                String msg = StrUtil.maxLength(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 500);
                int retry = ev.getRetryCount() == null ? 0 : ev.getRetryCount();
                int nextRetry = retry + 1;
                if (nextRetry >= maxRetry) {
                    markDead(ev.getId(), nextRetry, msg);
                    dead++;
                    log.warn("plat_outbox dead eventId={} type={} err={}", ev.getEventId(), ev.getEventType(), msg);
                } else {
                    Date nextAt = backoffAt(nextRetry, baseBackoffSec);
                    markFailed(ev.getId(), nextRetry, nextAt, msg);
                    failed++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processed", due.size());
        out.put("sent", sent);
        out.put("failed", failed);
        out.put("dead", dead);
        out.put("skipped", skipped);
        out.put("batchSize", batch);
        return out;
    }

    List<PlatOutboxEvent> listDue(int limit) {
        Date now = new Date();
        return outboxEventMapper.selectList(new QueryWrapper<PlatOutboxEvent>().lambda()
                .and(w -> w
                        .eq(PlatOutboxEvent::getStatus, STATUS_PENDING)
                        .or(o -> o
                                .eq(PlatOutboxEvent::getStatus, STATUS_FAILED)
                                .and(f -> f
                                        .isNull(PlatOutboxEvent::getNextRetryAt)
                                        .or()
                                        .le(PlatOutboxEvent::getNextRetryAt, now))))
                .and(w -> w
                        .isNull(PlatOutboxEvent::getNextRetryAt)
                        .or()
                        .le(PlatOutboxEvent::getNextRetryAt, now))
                .orderByAsc(PlatOutboxEvent::getCreateTime)
                .last("LIMIT " + limit));
    }

    /**
     * 软认领：把 next_retry_at 推到短期未来，降低多实例重复投递；handler 幂等（审计排水）。
     */
    boolean claim(String id, String expectedStatus) {
        if (StrUtil.isBlank(id)) {
            return false;
        }
        String st = StrUtil.blankToDefault(expectedStatus, STATUS_PENDING);
        if (!STATUS_PENDING.equals(st) && !STATUS_FAILED.equals(st)) {
            return false;
        }
        Date now = new Date();
        Date leaseUntil = new Date(now.getTime() + 120_000L);
        int n = outboxEventMapper.update(null, new UpdateWrapper<PlatOutboxEvent>().lambda()
                .eq(PlatOutboxEvent::getId, id)
                .eq(PlatOutboxEvent::getStatus, st)
                .and(w -> w.isNull(PlatOutboxEvent::getNextRetryAt).or().le(PlatOutboxEvent::getNextRetryAt, now))
                .set(PlatOutboxEvent::getNextRetryAt, leaseUntil));
        return n > 0;
    }

    boolean markSent(String id) {
        Date now = new Date();
        int n = outboxEventMapper.update(null, new UpdateWrapper<PlatOutboxEvent>().lambda()
                .eq(PlatOutboxEvent::getId, id)
                .in(PlatOutboxEvent::getStatus, STATUS_PENDING, STATUS_FAILED)
                .set(PlatOutboxEvent::getStatus, STATUS_SENT)
                .set(PlatOutboxEvent::getSentAt, now)
                .set(PlatOutboxEvent::getLastError, null)
                .set(PlatOutboxEvent::getNextRetryAt, null));
        return n > 0;
    }

    void markFailed(String id, int retryCount, Date nextRetryAt, String error) {
        outboxEventMapper.update(null, new UpdateWrapper<PlatOutboxEvent>().lambda()
                .eq(PlatOutboxEvent::getId, id)
                .in(PlatOutboxEvent::getStatus, STATUS_PENDING, STATUS_FAILED)
                .set(PlatOutboxEvent::getStatus, STATUS_FAILED)
                .set(PlatOutboxEvent::getRetryCount, retryCount)
                .set(PlatOutboxEvent::getNextRetryAt, nextRetryAt)
                .set(PlatOutboxEvent::getLastError, error));
    }

    void markDead(String id, int retryCount, String error) {
        outboxEventMapper.update(null, new UpdateWrapper<PlatOutboxEvent>().lambda()
                .eq(PlatOutboxEvent::getId, id)
                .in(PlatOutboxEvent::getStatus, STATUS_PENDING, STATUS_FAILED)
                .set(PlatOutboxEvent::getStatus, STATUS_DEAD)
                .set(PlatOutboxEvent::getRetryCount, retryCount)
                .set(PlatOutboxEvent::getNextRetryAt, null)
                .set(PlatOutboxEvent::getLastError, error));
    }

    static Date backoffAt(int retryCount, int baseSeconds) {
        int exp = Math.min(6, Math.max(0, retryCount - 1));
        int sec = baseSeconds * (1 << exp);
        Calendar c = Calendar.getInstance();
        c.add(Calendar.SECOND, sec);
        return c.getTime();
    }
}
