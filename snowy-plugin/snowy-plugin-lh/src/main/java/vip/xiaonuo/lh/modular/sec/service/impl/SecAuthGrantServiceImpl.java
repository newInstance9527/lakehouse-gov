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
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMapper;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.sec.entity.LhTrinoPrincipal;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.enums.LhOpsPrivilegeEnum;
import vip.xiaonuo.lh.modular.sec.enums.LhOpsResourceTypeEnum;
import vip.xiaonuo.lh.modular.sec.mapper.SecAuthGrantMapper;
import vip.xiaonuo.lh.modular.sec.service.LhTrinoPrincipalService;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class SecAuthGrantServiceImpl implements SecAuthGrantService {

    private static final String NOT_DELETE = "NOT_DELETE";
    /** 与 {@link vip.xiaonuo.lh.modular.query.support.CpQueryElevateGate} 对齐 */
    private static final String SCAN_ELEVATE_PRIVILEGE = "SCAN_ELEVATE";
    private static final String SCAN_ELEVATE_RESOURCE_TYPE = "adhoc";
    private static final String SCAN_ELEVATE_RESOURCE_ID = "platform";

    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private IgEtlDagMapper etlDagMapper;
    @Resource
    private GovMetricMapper metricMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private LhTrinoPrincipalService principalService;

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
        // 仅认 Grav 投影成功的 SELECT（grav_projected=1），避免 soft-fail 假授权放行
        return hasActiveSelectProjection(user.getId(), assetId);
    }

    @Override
    public List<String> listGrantedSelectAssetIds(String userId) {
        if (StrUtil.isBlank(userId)) {
            return List.of();
        }
        Date now = new Date();
        var qw = baseActiveGrantQw(userId, now);
        qw.eq(SecAuthGrant::getPrivilege, "SELECT")
                .eq(SecAuthGrant::getGravProjected, 1)
                .and(w -> w.isNull(SecAuthGrant::getRemark)
                        .or().notLike(SecAuthGrant::getRemark, "%soft-fail%"))
                .orderByDesc(SecAuthGrant::getUpdateTime)
                .last("LIMIT 500");
        List<SecAuthGrant> grants = grantMapper.selectList(qw);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String assetType = LhOpsResourceTypeEnum.ASSET.getValue();
        for (SecAuthGrant g : grants) {
            if (g == null) {
                continue;
            }
            String type = StrUtil.blankToDefault(g.getResourceType(), "").trim().toLowerCase(Locale.ROOT);
            if (StrUtil.isNotBlank(type) && !assetType.equals(type)) {
                continue;
            }
            String id = StrUtil.blankToDefault(g.getResourceId(), g.getAssetId());
            if (StrUtil.isNotBlank(id)) {
                ids.add(id.trim());
            }
        }
        return new ArrayList<>(ids);
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
    public boolean canUseDatasource(String datasourceId) {
        if (StrUtil.isBlank(datasourceId)) {
            return false;
        }
        if (LhLoginUsers.isSuperAdmin()) {
            return true;
        }
        return hasOpsPrivilege(LhOpsResourceTypeEnum.DATASOURCE.getValue(), datasourceId, LhOpsPrivilegeEnum.EDIT);
    }

    @Override
    public boolean canReadMetric(String metricId) {
        if (StrUtil.isBlank(metricId)) {
            return false;
        }
        if (LhLoginUsers.isSuperAdmin()) {
            return true;
        }
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        GovMetric metric = metricMapper.selectById(metricId);
        if (metric != null && isMetricOwner(metric, user)) {
            return true;
        }
        String type = LhOpsResourceTypeEnum.METRIC.getValue();
        if (hasActiveOpsPrivilege(user.getId(), type, metricId, LhOpsPrivilegeEnum.EDIT)) {
            return true;
        }
        return hasActivePrivilege(user.getId(), type, metricId, "SELECT");
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

    @Override
    public void assertCanEditMetric(GovMetric metric) {
        if (metric == null) {
            throw new CommonException("指标不存在");
        }
        if (LhLoginUsers.isSuperAdmin()) {
            return;
        }
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        if (isMetricOwner(metric, user)) {
            return;
        }
        if (hasActiveOpsPrivilege(user.getId(), LhOpsResourceTypeEnum.METRIC.getValue(), metric.getId(),
                LhOpsPrivilegeEnum.EDIT)) {
            return;
        }
        throw new CommonException(
                "指标「" + StrUtil.blankToDefault(metric.getMetricCode(), metric.getId()) + "」无编辑权："
                        + LhOwnerGuard.MSG_NEED_APPLY);
    }

    @Override
    public void assertCanReadMetric(GovMetric metric) {
        if (metric == null) {
            throw new CommonException("指标不存在");
        }
        if (canReadMetric(metric.getId())) {
            return;
        }
        throw new CommonException(
                "指标「" + StrUtil.blankToDefault(metric.getMetricCode(), metric.getId()) + "」无查询权："
                        + LhOwnerGuard.MSG_NEED_APPLY);
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
        if (LhOpsResourceTypeEnum.METRIC.getValue().equals(type)) {
            GovMetric metric = metricMapper.selectById(resourceId);
            return metric != null && isMetricOwner(metric, user);
        }
        return false;
    }

    /** 表查看只认 privilege=SELECT。操作权走 {@link #hasActiveOpsPrivilege}。 */
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

    /** 目录上锁：仅 Grav 投影成功的 SELECT（grav_projected=1）；排除历史 soft-fail 假投影。 */
    private boolean hasActiveSelectProjection(String userId, String assetId) {
        Date now = new Date();
        var qw = baseActiveGrantQw(userId, now);
        qw.eq(SecAuthGrant::getPrivilege, "SELECT")
                .eq(SecAuthGrant::getGravProjected, 1)
                .and(w -> w.isNull(SecAuthGrant::getRemark)
                        .or().notLike(SecAuthGrant::getRemark, "%soft-fail%"));
        applyResourceFilter(qw, LhOpsResourceTypeEnum.ASSET.getValue(), assetId);
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

    public static boolean isMetricOwner(GovMetric metric, SaBaseLoginUser user) {
        if (metric == null || user == null) {
            return false;
        }
        return LhOwnerGuard.isOwner(user, metric.getCreateUser(), metric.getOwner());
    }

    @Override
    public boolean hasScanElevateGrant() {
        if (LhLoginUsers.isSuperAdmin()) {
            return true;
        }
        String userId;
        try {
            userId = LhLoginUsers.requireUserId();
        } catch (CommonException e) {
            return false;
        }
        Date now = new Date();
        Long cnt = grantMapper.selectCount(baseActiveGrantQw(userId, now)
                .eq(SecAuthGrant::getPrivilege, SCAN_ELEVATE_PRIVILEGE)
                .eq(SecAuthGrant::getResourceType, SCAN_ELEVATE_RESOURCE_TYPE)
                .eq(SecAuthGrant::getResourceId, SCAN_ELEVATE_RESOURCE_ID));
        return cnt != null && cnt > 0;
    }

    @Override
    public SecAuthGrant createScanElevateFromApproval(String ticketId, String subjectId,
                                                      Date expiresAt, String remark) {
        if (StrUtil.isBlank(subjectId)) {
            throw new CommonException("抬额授权缺少主体");
        }
        // 同主体已有未过期抬额则续期/覆盖备注，避免重复行
        Date now = new Date();
        SecAuthGrant existing = grantMapper.selectOne(new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(SecAuthGrant::getStatus, "active")
                .eq(SecAuthGrant::getSubjectType, "user")
                .eq(SecAuthGrant::getSubjectId, subjectId)
                .eq(SecAuthGrant::getPrivilege, SCAN_ELEVATE_PRIVILEGE)
                .eq(SecAuthGrant::getResourceType, SCAN_ELEVATE_RESOURCE_TYPE)
                .eq(SecAuthGrant::getResourceId, SCAN_ELEVATE_RESOURCE_ID)
                .and(w -> w.isNull(SecAuthGrant::getExpiresAt).or().gt(SecAuthGrant::getExpiresAt, now))
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setTicketId(StrUtil.blankToDefault(ticketId, existing.getTicketId()));
            existing.setExpiresAt(expiresAt);
            existing.setRemark(StrUtil.blankToDefault(remark, existing.getRemark()));
            existing.setUpdateTime(now);
            existing.setUpdateUser(LhLoginUsers.requireUserId());
            grantMapper.updateById(existing);
            return existing;
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
        g.setResourceType(SCAN_ELEVATE_RESOURCE_TYPE);
        g.setResourceId(SCAN_ELEVATE_RESOURCE_ID);
        g.setPrivilege(SCAN_ELEVATE_PRIVILEGE);
        g.setGravProjected(0);
        g.setEffectiveAt(now);
        g.setExpiresAt(expiresAt);
        g.setDeleteFlag(NOT_DELETE);
        g.setCreateTime(now);
        g.setCreateUser(LhLoginUsers.requireUserId());
        grantMapper.insert(g);
        return g;
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
        if (typeEnum == LhOpsResourceTypeEnum.ASSET
                && "SELECT".equalsIgnoreCase(StrUtil.blankToDefault(privilege, ""))) {
            throw new CommonException("表 SELECT 只写入 Gravitino，不写入门户授权表");
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
        // 门户操作权只承接 EDIT/DELETE/MANAGE。表 SELECT 由 recordSelectProjection 在引擎授权成功后写入。
        return g;
    }

    @Override
    public SecAuthGrant recordSelectProjection(String ticketId, String subjectId, String assetId,
                                               String gravAssetId, Date expiresAt, String rowFilter,
                                               String policyId, String ws, String remark) {
        if (StrUtil.hasBlank(subjectId, assetId)) {
            throw new CommonException("SELECT 投影缺少主体或资产");
        }
        Date now = new Date();
        SecAuthGrant existing = grantMapper.selectOne(new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(SecAuthGrant::getStatus, "active")
                .eq(SecAuthGrant::getSubjectType, "user")
                .eq(SecAuthGrant::getSubjectId, subjectId)
                .eq(SecAuthGrant::getPrivilege, "SELECT")
                .and(w -> w.eq(SecAuthGrant::getAssetId, assetId)
                        .or().eq(SecAuthGrant::getResourceId, assetId))
                .last("limit 1"));
        if (existing != null) {
            existing.setTicketId(StrUtil.blankToDefault(ticketId, existing.getTicketId()));
            existing.setAssetId(assetId);
            existing.setResourceType(LhOpsResourceTypeEnum.ASSET.getValue());
            existing.setResourceId(assetId);
            existing.setGravAssetId(StrUtil.blankToDefault(gravAssetId, existing.getGravAssetId()));
            existing.setRowFilter(StrUtil.maxLength(StrUtil.nullToEmpty(rowFilter), 1024));
            if (StrUtil.isNotBlank(policyId)) {
                existing.setGravPolicyId(policyId);
            }
            existing.setGravProjected(1);
            existing.setExpiresAt(expiresAt);
            existing.setWs(StrUtil.blankToDefault(ws, existing.getWs()));
            existing.setRemark(StrUtil.blankToDefault(remark, existing.getRemark()));
            existing.setUpdateTime(now);
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            grantMapper.updateById(existing);
            return existing;
        }
        SecAuthGrant g = new SecAuthGrant();
        g.setId(IdUtil.getSnowflakeNextIdStr());
        g.setRevision(1);
        g.setStatus("active");
        g.setWs(StrUtil.blankToDefault(ws, "default"));
        g.setRemark(StrUtil.blankToDefault(remark, "SELECT projection; catalog only"));
        g.setTicketId(ticketId);
        g.setSubjectType("user");
        g.setSubjectId(subjectId);
        g.setResourceType(LhOpsResourceTypeEnum.ASSET.getValue());
        g.setResourceId(assetId);
        g.setAssetId(assetId);
        g.setGravAssetId(gravAssetId);
        g.setPrivilege("SELECT");
        g.setRowFilter(StrUtil.maxLength(StrUtil.nullToEmpty(rowFilter), 1024));
        g.setGravPolicyId(StrUtil.blankToDefault(policyId, null));
        g.setGravProjected(1);
        g.setEffectiveAt(now);
        g.setExpiresAt(expiresAt);
        g.setDeleteFlag(NOT_DELETE);
        g.setCreateTime(now);
        try {
            g.setCreateUser(LhLoginUsers.requireUserId());
        } catch (Exception ignored) {
            g.setCreateUser(subjectId);
        }
        grantMapper.insert(g);
        return g;
    }

    @Override
    public int expireSelectProjection(String ticketId) {
        if (StrUtil.isBlank(ticketId)) {
            return 0;
        }
        Date now = new Date();
        UpdateWrapper<SecAuthGrant> uw = new UpdateWrapper<>();
        uw.eq("delete_flag", NOT_DELETE)
                .eq("status", "active")
                .eq("privilege", "SELECT")
                .eq("ticket_id", ticketId)
                .set("status", "expired")
                .set("update_time", now);
        return grantMapper.update(null, uw);
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
            LhTrinoPrincipal principal = principalService.findActive(grant.getSubjectId());
            if (principal == null || StrUtil.isBlank(principal.getTrinoUser())) {
                out.put("skipped", true);
                out.put("message", "no_trino_principal");
                return out;
            }
            Map<String, Object> r = gravitinoClient.grantTablePrivilege(
                    ref.getGravMetalake(),
                    ref.getGravCatalog(),
                    ref.getGravSchema(),
                    ref.getGravTable(),
                    principal.getTrinoUser(),
                    StrUtil.blankToDefault(grant.getPrivilege(), "SELECT"));
            out.putAll(r);
            out.put("projected", Boolean.TRUE.equals(r.get("ok")));
            out.put("trinoUser", principal.getTrinoUser());
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
                .apply("NOT (privilege = 'SELECT' AND grav_projected = 1)")
                .set("status", "expired")
                .set("update_time", now);
        return grantMapper.update(null, uw);
    }
}
