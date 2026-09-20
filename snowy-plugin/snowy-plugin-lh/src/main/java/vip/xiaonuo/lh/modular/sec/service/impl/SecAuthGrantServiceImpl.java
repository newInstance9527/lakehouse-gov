package vip.xiaonuo.lh.modular.sec.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.auth.LhOwnerGuard;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlDagMapper;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.enums.LhOpsPrivilegeEnum;
import vip.xiaonuo.lh.modular.sec.enums.LhOpsResourceTypeEnum;
import vip.xiaonuo.lh.modular.sec.mapper.SecAuthGrantMapper;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class SecAuthGrantServiceImpl implements SecAuthGrantService {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private IgEtlDagMapper etlDagMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private GravitinoClient gravitinoClient;

    @Override
    public boolean hasTableReadGrant(String assetId) {
        if (StrUtil.isBlank(assetId)) {
            return false;
        }
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        GovAsset asset = assetMapper.selectById(assetId);
        if (asset != null && isAssetOwner(asset, user)) {
            return true;
        }
        return hasActivePrivilege(user.getId(), LhOpsResourceTypeEnum.ASSET.getValue(), assetId, null);
    }

    @Override
    public boolean hasManageGrant(String assetId) {
        return hasManageGrant(LhOpsResourceTypeEnum.ASSET.getValue(), assetId);
    }

    @Override
    public boolean hasManageGrant(String resourceType, String resourceId) {
        return hasOpsPrivilege(resourceType, resourceId, LhOpsPrivilegeEnum.MANAGE);
    }

    @Override
    public boolean hasOpsPrivilege(String resourceType, String resourceId, LhOpsPrivilegeEnum needed) {
        if (needed == null || StrUtil.isBlank(resourceType) || StrUtil.isBlank(resourceId)) {
            return false;
        }
        String type = resourceType.trim().toLowerCase(Locale.ROOT);
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        if (isResourceOwner(type, resourceId, user)) {
            return true;
        }
        return hasActiveOpsPrivilege(user.getId(), type, resourceId, needed);
    }

    @Override
    public void assertCanEditAsset(GovAsset asset) {
        assertAssetPrivilege(asset, LhOpsPrivilegeEnum.EDIT, "编辑");
    }

    @Override
    public void assertCanDeleteAsset(GovAsset asset) {
        assertAssetPrivilege(asset, LhOpsPrivilegeEnum.DELETE, "删除");
    }

    @Override
    public void assertCanEditDatasource(LhDatasource ds) {
        assertDatasourcePrivilege(ds, LhOpsPrivilegeEnum.EDIT, "编辑");
    }

    @Override
    public void assertCanDeleteDatasource(LhDatasource ds) {
        assertDatasourcePrivilege(ds, LhOpsPrivilegeEnum.DELETE, "删除");
    }

    @Override
    public void assertCanEditEtl(IgEtlDag dag) {
        assertEtlPrivilege(dag, LhOpsPrivilegeEnum.EDIT, "编辑");
    }

    @Override
    public void assertCanDeleteEtl(IgEtlDag dag) {
        assertEtlPrivilege(dag, LhOpsPrivilegeEnum.DELETE, "删除");
    }

    private void assertAssetPrivilege(GovAsset asset, LhOpsPrivilegeEnum needed, String actionLabel) {
        if (asset == null) {
            throw new CommonException("资产不存在");
        }
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        if (isAssetOwner(asset, user)) {
            return;
        }
        if (hasActiveOpsPrivilege(user.getId(), LhOpsResourceTypeEnum.ASSET.getValue(), asset.getId(), needed)) {
            return;
        }
        throw new CommonException(
                "资产「" + StrUtil.blankToDefault(asset.getAssetCode(), asset.getId()) + "」无" + actionLabel + "权："
                        + LhOwnerGuard.MSG_NEED_APPLY);
    }

    private void assertDatasourcePrivilege(LhDatasource ds, LhOpsPrivilegeEnum needed, String actionLabel) {
        if (ds == null) {
            throw new CommonException("数据源不存在");
        }
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        if (LhOwnerGuard.isOwner(user, ds.getCreateUser(), ds.getOwner())) {
            return;
        }
        if (hasActiveOpsPrivilege(user.getId(), LhOpsResourceTypeEnum.DATASOURCE.getValue(), ds.getId(), needed)) {
            return;
        }
        throw new CommonException(
                "数据源「" + StrUtil.blankToDefault(ds.getName(), ds.getId()) + "」无" + actionLabel + "权："
                        + LhOwnerGuard.MSG_NEED_APPLY);
    }

    private void assertEtlPrivilege(IgEtlDag dag, LhOpsPrivilegeEnum needed, String actionLabel) {
        if (dag == null) {
            throw new CommonException("ETL 任务不存在");
        }
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        if (LhOwnerGuard.isOwner(user, dag.getCreateUser(), dag.getOwner())) {
            return;
        }
        if (hasActiveOpsPrivilege(user.getId(), LhOpsResourceTypeEnum.ETL.getValue(), dag.getId(), needed)) {
            return;
        }
        throw new CommonException(
                "ETL「" + StrUtil.blankToDefault(dag.getDagCode(), dag.getId()) + "」无" + actionLabel + "权："
                        + LhOwnerGuard.MSG_NEED_APPLY);
    }

    private boolean isResourceOwner(String type, String resourceId, SaBaseLoginUser user) {
        if (LhOpsResourceTypeEnum.ASSET.getValue().equals(type)) {
            GovAsset asset = assetMapper.selectById(resourceId);
            return asset != null && isAssetOwner(asset, user);
        }
        if (LhOpsResourceTypeEnum.DATASOURCE.getValue().equals(type)) {
            LhDatasource ds = datasourceMapper.selectById(resourceId);
            return ds != null && LhOwnerGuard.isOwner(user, ds.getCreateUser(), ds.getOwner());
        }
        if (LhOpsResourceTypeEnum.ETL.getValue().equals(type)) {
            IgEtlDag dag = etlDagMapper.selectById(resourceId);
            return dag != null && LhOwnerGuard.isOwner(user, dag.getCreateUser(), dag.getOwner());
        }
        return false;
    }

    /** 表读：任意 active grant（含 SELECT）；操作权：按 needed 匹配 EDIT/DELETE/MANAGE */
    private boolean hasActivePrivilege(String userId, String resourceType, String resourceId, String privilegeExact) {
        Date now = new Date();
        var qw = baseActiveGrantQw(userId, now);
        if (StrUtil.isNotBlank(privilegeExact)) {
            qw.eq(SecAuthGrant::getPrivilege, privilegeExact);
        }
        applyResourceFilter(qw, resourceType, resourceId);
        Long cnt = grantMapper.selectCount(qw);
        return cnt != null && cnt > 0;
    }

    private boolean hasActiveOpsPrivilege(String userId, String resourceType, String resourceId,
                                          LhOpsPrivilegeEnum needed) {
        Date now = new Date();
        var qw = baseActiveGrantQw(userId, now);
        Set<String> values = LhOpsPrivilegeEnum.matchingValues(needed);
        qw.in(SecAuthGrant::getPrivilege, values);
        applyResourceFilter(qw, resourceType, resourceId);
        Long cnt = grantMapper.selectCount(qw);
        return cnt != null && cnt > 0;
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecAuthGrant> baseActiveGrantQw(
            String userId, Date now) {
        return new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(SecAuthGrant::getStatus, "active")
                .eq(SecAuthGrant::getSubjectType, "user")
                .eq(SecAuthGrant::getSubjectId, userId)
                .and(w -> w.isNull(SecAuthGrant::getExpiresAt).or().gt(SecAuthGrant::getExpiresAt, now));
    }

    private void applyResourceFilter(
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SecAuthGrant> qw,
            String resourceType, String resourceId) {
        String type = StrUtil.blankToDefault(resourceType, "").toLowerCase(Locale.ROOT);
        if (LhOpsResourceTypeEnum.ASSET.getValue().equals(type)) {
            qw.and(w -> w
                    .and(x -> x.eq(SecAuthGrant::getResourceType, type).eq(SecAuthGrant::getResourceId, resourceId))
                    .or(x -> x.eq(SecAuthGrant::getAssetId, resourceId)
                            .and(y -> y.isNull(SecAuthGrant::getResourceType)
                                    .or().eq(SecAuthGrant::getResourceType, "")
                                    .or().eq(SecAuthGrant::getResourceType, type))));
        } else {
            qw.eq(SecAuthGrant::getResourceType, type).eq(SecAuthGrant::getResourceId, resourceId);
        }
    }

    public static boolean isAssetOwner(GovAsset asset, SaBaseLoginUser user) {
        if (asset == null || user == null) {
            return false;
        }
        return LhOwnerGuard.isOwner(user, asset.getCreateUser(), asset.getTechOwner(), asset.getBizOwner());
    }

    @Override
    public SecAuthGrant createFromApproval(String ticketId, String subjectId,
                                           String resourceType, String resourceId,
                                           String assetId, String gravAssetId,
                                           String privilege, Date expiresAt, String remark) {
        LhOpsResourceTypeEnum typeEnum = LhOpsResourceTypeEnum.requireEnabled(resourceType);
        if (StrUtil.isBlank(resourceId)) {
            throw new CommonException("resourceId 不能为空");
        }
        SecAuthGrant g = new SecAuthGrant();
        g.setId(IdUtil.getSnowflakeNextIdStr());
        g.setRevision(1);
        g.setStatus("active");
        g.setWs("default");
        g.setRemark(remark);
        g.setTicketId(ticketId);
        g.setSubjectType("user");
        g.setSubjectId(subjectId);
        g.setResourceType(typeEnum.getValue());
        g.setResourceId(resourceId.trim());
        if (typeEnum == LhOpsResourceTypeEnum.ASSET) {
            g.setAssetId(StrUtil.blankToDefault(assetId, resourceId));
            g.setGravAssetId(gravAssetId);
        }
        g.setPrivilege(StrUtil.blankToDefault(privilege, "SELECT"));
        g.setGravProjected(0);
        g.setEffectiveAt(new Date());
        g.setExpiresAt(expiresAt);
        g.setDeleteFlag(NOT_DELETE);
        g.setCreateTime(new Date());
        g.setCreateUser(LhLoginUsers.requireUserId());
        grantMapper.insert(g);
        // 门户 sec_auth_grant 为授权 SoT；审批通过不投影 Grav（引擎 ACL 另册）。
        // resource_manage(EDIT|DELETE|MANAGE) 与 table_read(SELECT) 均仅写门户。
        return g;
    }

    @Override
    public Map<String, Object> projectGravAcl(SecAuthGrant grant) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projected", false);
        if (grant == null || StrUtil.isBlank(grant.getGravAssetId())) {
            out.put("skipped", true);
            out.put("message", "no_grav_asset_portal_only");
            return out;
        }
        CbGravAssetRef ref = gravAssetRefMapper.selectById(grant.getGravAssetId());
        if (ref == null) {
            out.put("skipped", true);
            out.put("message", "grav_ref_missing");
            return out;
        }
        try {
            Map<String, Object> r = gravitinoClient.grantTablePrivilege(
                    ref.getGravMetalake(),
                    ref.getGravCatalog(),
                    ref.getGravSchema(),
                    ref.getGravTable(),
                    grant.getSubjectId(),
                    StrUtil.blankToDefault(grant.getPrivilege(), "SELECT"));
            out.putAll(r);
            out.put("projected", Boolean.TRUE.equals(r.get("ok")));
            if (r.get("policyId") != null) {
                out.put("policyId", r.get("policyId"));
            }
        } catch (Exception e) {
            log.warn("Grav ACL soft-fail: {}", e.getMessage());
            out.put("skipped", false);
            out.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
        }
        return out;
    }

    @Override
    public int expireDueGrants() {
        Date now = new Date();
        UpdateWrapper<SecAuthGrant> uw = new UpdateWrapper<>();
        uw.eq("delete_flag", NOT_DELETE)
                .eq("status", "active")
                .isNotNull("expires_at")
                .le("expires_at", now)
                .set("status", "expired")
                .set("update_time", now);
        return grantMapper.update(null, uw);
    }
}
