package vip.xiaonuo.lh.modular.export.service;

import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;

import java.util.List;
import java.util.Map;

/**
 * 出湖出库审计正式落库（A7）
 */
public interface ExportAuditService {

    String EVENT_APPROVED = "approved";
    String EVENT_EXPIRE_STOP = "expire_stop";
    String EVENT_NOTICE_PURGE = "notice_purge";

    /**
     * 写入一条出库审计；可选 soft-fail 打 Grav 表属性。
     *
     * @param extras 可选：dagId/dagCode/nodeKey/detail 等
     * @return 落库行摘要
     */
    Map<String, Object> record(String eventType, ApplyTicket ticket, Map<String, Object> extras);

    /** 审计列表（门户 SoT = gov_export_audit） */
    List<Map<String, Object>> list(String ws, String ticketNo);
}
