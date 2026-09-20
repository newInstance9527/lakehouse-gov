package vip.xiaonuo.lh.modular.sec.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.mapper.SecAuthGrantMapper;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class SecAuthGrantServiceImpl implements SecAuthGrantService {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private GravitinoClient gravitinoClient;

    @Override
    public boolean hasTableReadGrant(String assetId) {
        if (LhLoginUsers.isSuperAdmin()) {
            return true;
        }
        if (StrUtil.isBlank(assetId)) {
            return false;
        }
        String userId = LhLoginUsers.requireUserId();
        Date now = new Date();
        Long cnt = grantMapper.selectCount(new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(SecAuthGrant::getStatus, "active")
                .eq(SecAuthGrant::getSubjectType, "user")
                .eq(SecAuthGrant::getSubjectId, userId)
                .eq(SecAuthGrant::getAssetId, assetId)
                .and(w -> w.isNull(SecAuthGrant::getExpiresAt).or().gt(SecAuthGrant::getExpiresAt, now)));
        return cnt != null && cnt > 0;
    }

    @Override
    public SecAuthGrant createFromApproval(String ticketId, String subjectId, String assetId,
                                           String gravAssetId, String privilege, Date expiresAt, String remark) {
        SecAuthGrant g = new SecAuthGrant();
        g.setId(IdUtil.getSnowflakeNextIdStr());
        g.setRevision(1);
        g.setStatus("active");
        g.setWs("default");
        g.setRemark(remark);
        g.setTicketId(ticketId);
        g.setSubjectType("user");
        g.setSubjectId(subjectId);
        g.setAssetId(assetId);
        g.setGravAssetId(gravAssetId);
        g.setPrivilege(StrUtil.blankToDefault(privilege, "SELECT"));
        g.setGravProjected(0);
        g.setEffectiveAt(new Date());
        g.setExpiresAt(expiresAt);
        g.setDeleteFlag(NOT_DELETE);
        g.setCreateTime(new Date());
        g.setCreateUser(LhLoginUsers.requireUserId());
        grantMapper.insert(g);
        Map<String, Object> proj = projectGravAcl(g);
        if (Boolean.TRUE.equals(proj.get("projected"))) {
            g.setGravProjected(1);
            g.setGravPolicyId(String.valueOf(proj.getOrDefault("policyId", "")));
            g.setUpdateTime(new Date());
            grantMapper.updateById(g);
        } else if (proj.get("message") != null) {
            g.setRemark(StrUtil.blankToDefault(g.getRemark(), "") + " | grav:" + proj.get("message"));
            g.setUpdateTime(new Date());
            grantMapper.updateById(g);
        }
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
}
