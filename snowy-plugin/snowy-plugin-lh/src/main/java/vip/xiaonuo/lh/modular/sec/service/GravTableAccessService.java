package vip.xiaonuo.lh.modular.sec.service;

import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicketItem;

import java.util.Map;

/**
 * 表数据权限以 Gravitino 为准。门户 SELECT 行只是授权成功后的目录投影，执行不读它。
 */
public interface GravTableAccessService {

    /**
     * 表读申请通过：先授予 Gravitino SELECT，成功后再写门户投影（目录上锁用）。
     * Grav 失败：不写 sec_auth_grant SELECT 投影，返回 softFail=true 与 gravMessage（审批方可据此提示，不产生假授权）。
     */
    Map<String, Object> grantTableRead(ApplyTicket ticket, ApplyTicketItem item, String privilege, String rowFilter);

    /** 当前用户能否 SELECT 该资产。问 Trino/Gravitino，不读门户授权表。未映射返回 false。 */
    boolean canCurrentSelect(String assetId);

    /** 到期的表读申请在 Gravitino 回收。 */
    int revokeExpired();
}
