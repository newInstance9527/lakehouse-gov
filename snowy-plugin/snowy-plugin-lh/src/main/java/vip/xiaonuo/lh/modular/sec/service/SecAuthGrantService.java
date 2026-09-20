package vip.xiaonuo.lh.modular.sec.service;

import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;

import java.util.Date;
import java.util.Map;

public interface SecAuthGrantService {

    /** 当前登录用户是否具备资产表级读权限（超管短路） */
    boolean hasTableReadGrant(String assetId);

    SecAuthGrant createFromApproval(String ticketId, String subjectId, String assetId,
                                    String gravAssetId, String privilege, Date expiresAt, String remark);

    /** soft-fail 投影 Grav ACL；不可投影仅记门户 grant */
    Map<String, Object> projectGravAcl(SecAuthGrant grant);
}
