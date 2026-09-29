package vip.xiaonuo.lh.modular.sec.service;

import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.enums.LhOpsPrivilegeEnum;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface SecAuthGrantService {

    /**
     * 表级查看：拥有者或未过期且已 Grav 投影成功的 SELECT。
     * 不含 EDIT/DELETE/MANAGE；假投影（grav_projected≠1）不放行。
     */
    boolean hasTableReadGrant(String assetId);

    /**
     * 当前用户已 Grav 投影成功的 SELECT 资产 ID（不含 Owner 自有；与 {@link #hasTableReadGrant} 投影谓词一致）。
     * 供 AI list_my_assets 倒排，避免扫全站资产。
     */
    List<String> listGrantedSelectAssetIds(String userId);

    /** 资产管理权：拥有者或 MANAGE grant（兼容旧 assetId） */
    boolean hasManageGrant(String assetId);

    /** 通用：当前用户是否持有某资源的 MANAGE grant（或资产拥有者） */
    boolean hasManageGrant(String resourceType, String resourceId);

    /**
     * 数据服务构建 / 列表：是否可用该数据源（超管、拥有者或 EDIT/MANAGE grant）。
     */
    boolean canUseDatasource(String datasourceId);

    /**
     * 指标读/试跑：超管、拥有者，或未过期 SELECT / EDIT / MANAGE。
     */
    boolean canReadMetric(String metricId);

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

    /** 指标写：超管、拥有者或 EDIT/MANAGE */
    void assertCanEditMetric(GovMetric metric);

    /** 指标试跑/查询：见 {@link #canReadMetric} */
    void assertCanReadMetric(GovMetric metric);

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
     * 即席扫描抬额：当前用户是否持有未过期的 SCAN_ELEVATE（resource_type=adhoc）。
     * 超管视为已授权。
     */
    boolean hasScanElevateGrant();

    /**
     * 审批通过写即席扫描抬额 grant（privilege=SCAN_ELEVATE，resource=adhoc/platform）。
     */
    SecAuthGrant createScanElevateFromApproval(String ticketId, String subjectId,
                                               Date expiresAt, String remark);

    /**
     * 审批通过写 grant。
     * @param resourceType asset/datasource/etl/metric（已启用）
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

    /** 将 expires_at 已过且仍为 active 的操作权标为 expired。SELECT 投影留给 Gravitino 回收成功后再标。 */
    int expireDueGrants();

    /**
     * Gravitino 表读授权已成功之后，写一条门户 SELECT 投影，只给目录上锁用。
     * 不调用引擎。已有同主体同资产的 active SELECT 则更新。
     */
    SecAuthGrant recordSelectProjection(String ticketId, String subjectId, String assetId,
                                        String gravAssetId, Date expiresAt, String rowFilter,
                                        String policyId, String ws, String remark);

    /** Gravitino 回收成功后，把该申请单的 SELECT 投影标为 expired。 */
    int expireSelectProjection(String ticketId);
}
