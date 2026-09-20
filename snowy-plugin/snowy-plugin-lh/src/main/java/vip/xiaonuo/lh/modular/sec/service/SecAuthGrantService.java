package vip.xiaonuo.lh.modular.sec.service;

import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.enums.LhOpsPrivilegeEnum;

import java.util.Date;
import java.util.Map;

public interface SecAuthGrantService {

    /** 表级读/预览：拥有者或有效 SELECT 类 grant（超管不短路） */
    boolean hasTableReadGrant(String assetId);

    /** 资产管理权：拥有者或 MANAGE grant（兼容旧 assetId） */
    boolean hasManageGrant(String assetId);

    /** 通用：当前用户是否持有某资源的 MANAGE grant（或资产拥有者） */
    boolean hasManageGrant(String resourceType, String resourceId);

    /**
     * 是否具备所需操作能力（owner 或 privilege 满足；MANAGE 覆盖 EDIT/DELETE）。
     */
    boolean hasOpsPrivilege(String resourceType, String resourceId, LhOpsPrivilegeEnum needed);

    void assertCanEditAsset(GovAsset asset);

    void assertCanDeleteAsset(GovAsset asset);

    void assertCanEditDatasource(LhDatasource ds);

    void assertCanDeleteDatasource(LhDatasource ds);

    void assertCanEditEtl(IgEtlDag dag);

    void assertCanDeleteEtl(IgEtlDag dag);

    /** @deprecated 用 assertCanEdit*；保留为编辑能力别名 */
    @Deprecated
    default void assertCanManageAsset(GovAsset asset) {
        assertCanEditAsset(asset);
    }

    /** @deprecated 用 assertCanEdit* */
    @Deprecated
    default void assertCanManageDatasource(LhDatasource ds) {
        assertCanEditDatasource(ds);
    }

    /** @deprecated 用 assertCanEdit* */
    @Deprecated
    default void assertCanManageEtl(IgEtlDag dag) {
        assertCanEditEtl(dag);
    }

    /**
     * 审批通过写 grant。
     * @param resourceType asset/datasource/etl（已启用）
     * @param resourceId 资源主键
     * @param assetId 仅 asset 时填，兼容 Grav
     * @param gravAssetId 可选
     */
    SecAuthGrant createFromApproval(String ticketId, String subjectId,
                                    String resourceType, String resourceId,
                                    String assetId, String gravAssetId,
                                    String privilege, Date expiresAt, String remark);

    /** @deprecated 兼容旧签名：默认 resource_type=asset */
    @Deprecated
    default SecAuthGrant createFromApproval(String ticketId, String subjectId, String assetId,
                                            String gravAssetId, String privilege, Date expiresAt, String remark) {
        return createFromApproval(ticketId, subjectId, "asset", assetId, assetId, gravAssetId,
                privilege, expiresAt, remark);
    }

    /**
     * 可选引擎 ACL 投影（soft-fail）。审批 {@link #createFromApproval} 不再自动调用；
     * 产品以门户 sec_auth_grant 为 SoT，引擎 ACL 另册。
     */
    Map<String, Object> projectGravAcl(SecAuthGrant grant);

    /** 将 expires_at 已过且仍为 active 的 grant 标为 expired；返回更新条数 */
    int expireDueGrants();
}
