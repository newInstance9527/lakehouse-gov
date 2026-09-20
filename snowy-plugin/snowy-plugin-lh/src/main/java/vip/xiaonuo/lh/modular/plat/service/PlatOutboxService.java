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
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.modular.plat.entity.PlatOutboxEvent;
import vip.xiaonuo.lh.modular.plat.mapper.PlatOutboxEventMapper;

import java.util.Date;
import java.util.Map;

/**
 * 平台发件箱写入（同事务 pending；派发器后续消费）
 *
 * @author lakehouse
 * @date 2026/3/19
 */
@Slf4j
@Service
public class PlatOutboxService {

    public static final String STATUS_PENDING = "pending";

    @Resource
    private PlatOutboxEventMapper outboxEventMapper;

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
}
