package vip.xiaonuo.lh.modular.compliance.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.compliance.entity.GovDelTarget;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.entity.SecMaskPolicyProj;
import vip.xiaonuo.lh.modular.sec.mapper.SecAuthGrantMapper;
import vip.xiaonuo.lh.modular.sec.mapper.SecMaskPolicyProjMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 限制处理安全联动：门户撤 {@code sec_auth_grant} + 写 {@code sec_mask_policy_proj} 强制脱敏
 * + Gravitino 表权 soft-fail revoke。
 */
@Slf4j
@Component
public class GovDelRestrictSupport {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String MASK_COL = "*";
    private static final String MASK_ALGO = "compliance_redact";

    @Resource
    private GovAssetMapper govAssetMapper;
    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private SecMaskPolicyProjMapper maskMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private GravitinoClient gravitinoClient;

    public Map<String, Object> applySecurity(String ws, List<GovDelTarget> targets) {
        int grantsRevoked = 0;
        int masksUpserted = 0;
        int gravRevoked = 0;
        int gravFailed = 0;
        List<String> notes = new ArrayList<>();
        Set<String> seenAssets = new LinkedHashSet<>();
        Date now = new Date();

        for (GovDelTarget t : targets) {
            if (t == null || StrUtil.isBlank(t.getObjectFqn())) {
                continue;
            }
            GovAsset asset = resolveAsset(t.getObjectFqn());
            if (asset == null) {
                notes.add(t.getObjectFqn() + ": 未匹配 gov_asset，跳过 ACL/mask/Grav");
                continue;
            }
            if (!seenAssets.add(asset.getId())) {
                continue;
            }
            CbGravAssetRef gravRef = resolveGravRef(asset);
            String gravId = gravRef != null ? gravRef.getId()
                    : StrUtil.trim(asset.getGravAssetId());

            List<SecAuthGrant> active = listActiveGrants(asset.getId(), gravId);
            Map<String, Object> gravOut = softRevokeGrav(gravRef, active);
            gravRevoked += gravOut.get("revoked") instanceof Number n ? n.intValue() : 0;
            gravFailed += gravOut.get("failed") instanceof Number n ? n.intValue() : 0;
            if (gravOut.get("note") instanceof String note && StrUtil.isNotBlank(note)) {
                notes.add(asset.getAssetCode() + ": " + note);
            }

            grantsRevoked += revokeGrants(asset.getId(), gravId, now);
            if (StrUtil.isNotBlank(gravId)) {
                if (upsertComplianceMask(ws, gravId, now)) {
                    masksUpserted++;
                }
            } else {
                notes.add(asset.getAssetCode() + ": 无 grav_asset_id，仅撤门户 grant");
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grantsRevoked", grantsRevoked);
        out.put("masksUpserted", masksUpserted);
        out.put("gravRevoked", gravRevoked);
        out.put("gravFailed", gravFailed);
        out.put("gravRevoke", gravFailed > 0 ? "partial" : (gravRevoked > 0 ? "ok" : "noop"));
        out.put("notes", notes);
        out.put("tagsHint", "禁出湖/API/训练由 export/runtime 门禁读 restricted 状态");
        return out;
    }

    private List<SecAuthGrant> listActiveGrants(String assetId, String gravAssetId) {
        return grantMapper.selectList(new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(SecAuthGrant::getStatus, "active")
                .and(w -> {
                    w.eq(SecAuthGrant::getAssetId, assetId)
                            .or().eq(SecAuthGrant::getResourceId, assetId);
                    if (StrUtil.isNotBlank(gravAssetId)) {
                        w.or().eq(SecAuthGrant::getGravAssetId, gravAssetId);
                    }
                }));
    }

    /**
     * Grav 在线 revoke：按活跃 grant 的 subjectId 回收 SELECT（失败不抛，记 note）。
     */
    private Map<String, Object> softRevokeGrav(CbGravAssetRef ref, List<SecAuthGrant> grants) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revoked", 0);
        out.put("failed", 0);
        if (ref == null || StrUtil.hasBlank(ref.getGravMetalake(), ref.getGravCatalog(),
                ref.getGravSchema(), ref.getGravTable())) {
            out.put("note", "无完整 Grav 坐标，跳过在线 revoke");
            return out;
        }
        if (grants == null || grants.isEmpty()) {
            out.put("note", "无活跃 grant，跳过 Grav revoke");
            return out;
        }
        Set<String> principals = new LinkedHashSet<>();
        for (SecAuthGrant g : grants) {
            if (StrUtil.isNotBlank(g.getSubjectId())) {
                principals.add(g.getSubjectId().trim());
            }
        }
        if (principals.isEmpty()) {
            out.put("note", "grant 无 subjectId，跳过 Grav revoke");
            return out;
        }
        int ok = 0;
        int fail = 0;
        List<String> failMsgs = new ArrayList<>();
        for (String principal : principals) {
            String privilege = "SELECT";
            for (SecAuthGrant g : grants) {
                if (principal.equals(StrUtil.trim(g.getSubjectId())) && StrUtil.isNotBlank(g.getPrivilege())) {
                    privilege = g.getPrivilege().trim();
                    break;
                }
            }
            try {
                Map<String, Object> r = gravitinoClient.revokeTablePrivilege(
                        ref.getGravMetalake(), ref.getGravCatalog(), ref.getGravSchema(),
                        ref.getGravTable(), principal, privilege);
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    ok++;
                } else {
                    fail++;
                    failMsgs.add(principal + ":" + StrUtil.blankToDefault((String) r.get("message"), "fail"));
                }
            } catch (Exception e) {
                fail++;
                failMsgs.add(principal + ":" + StrUtil.blankToDefault(e.getMessage(), "exception"));
                log.warn("compliance grav revoke soft-fail principal={} table={}: {}",
                        principal, ref.getGravTable(), e.getMessage());
            }
        }
        out.put("revoked", ok);
        out.put("failed", fail);
        if (fail > 0) {
            out.put("note", "Grav revoke soft-fail " + fail + "："
                    + StrUtil.maxLength(String.join("; ", failMsgs), 200));
        }
        return out;
    }

    private GovAsset resolveAsset(String objectFqn) {
        String code = objectFqn.trim();
        String bare = bare(code);
        List<GovAsset> rows = govAssetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(GovAsset::getAssetCode, code)
                        .or().eq(GovAsset::getName, code)
                        .or().eq(GovAsset::getAssetCode, bare)
                        .or().eq(GovAsset::getName, bare)
                        .or().likeLeft(GovAsset::getAssetCode, "." + bare))
                .last("LIMIT 5"));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        for (GovAsset a : rows) {
            if (code.equalsIgnoreCase(a.getAssetCode()) || code.equalsIgnoreCase(a.getName())) {
                return a;
            }
        }
        return rows.get(0);
    }

    private CbGravAssetRef resolveGravRef(GovAsset asset) {
        if (asset == null) {
            return null;
        }
        if (StrUtil.isNotBlank(asset.getGravAssetId())) {
            CbGravAssetRef byPk = gravAssetRefMapper.selectById(asset.getGravAssetId().trim());
            if (byPk != null && NOT_DELETE.equals(StrUtil.blankToDefault(byPk.getDeleteFlag(), NOT_DELETE))) {
                return byPk;
            }
        }
        List<CbGravAssetRef> byId = gravAssetRefMapper.selectList(new QueryWrapper<CbGravAssetRef>().lambda()
                .eq(CbGravAssetRef::getDeleteFlag, NOT_DELETE)
                .eq(CbGravAssetRef::getId, asset.getId())
                .last("LIMIT 1"));
        if (byId != null && !byId.isEmpty()) {
            return byId.get(0);
        }
        String bare = bare(StrUtil.blankToDefault(asset.getAssetCode(), asset.getName()));
        if (StrUtil.isBlank(bare)) {
            return null;
        }
        List<CbGravAssetRef> byName = gravAssetRefMapper.selectList(new QueryWrapper<CbGravAssetRef>().lambda()
                .eq(CbGravAssetRef::getDeleteFlag, NOT_DELETE)
                .eq(CbGravAssetRef::getGravTable, bare)
                .last("LIMIT 1"));
        return byName == null || byName.isEmpty() ? null : byName.get(0);
    }

    private int revokeGrants(String assetId, String gravAssetId, Date now) {
        UpdateWrapper<SecAuthGrant> uw = new UpdateWrapper<>();
        uw.lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(SecAuthGrant::getStatus, "active")
                .and(w -> {
                    w.eq(SecAuthGrant::getAssetId, assetId)
                            .or().eq(SecAuthGrant::getResourceId, assetId);
                    if (StrUtil.isNotBlank(gravAssetId)) {
                        w.or().eq(SecAuthGrant::getGravAssetId, gravAssetId);
                    }
                })
                .set(SecAuthGrant::getStatus, "revoked")
                .set(SecAuthGrant::getExpiresAt, now)
                .set(SecAuthGrant::getUpdateTime, now)
                .set(SecAuthGrant::getRemark, "compliance restrict revoke");
        return grantMapper.update(null, uw);
    }

    private boolean upsertComplianceMask(String ws, String gravAssetId, Date now) {
        SecMaskPolicyProj exist = maskMapper.selectOne(new QueryWrapper<SecMaskPolicyProj>().lambda()
                .eq(SecMaskPolicyProj::getGravAssetId, gravAssetId)
                .eq(SecMaskPolicyProj::getColumnName, MASK_COL)
                .eq(SecMaskPolicyProj::getMaskAlgo, MASK_ALGO)
                .eq(SecMaskPolicyProj::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (exist != null) {
            exist.setStatus("active");
            exist.setSensitivity("机密");
            exist.setRemark("compliance restrict force mask");
            exist.setUpdateTime(now);
            maskMapper.updateById(exist);
            return true;
        }
        SecMaskPolicyProj m = new SecMaskPolicyProj();
        m.setId(IdUtil.getSnowflakeNextIdStr());
        m.setRevision(1);
        m.setStatus("active");
        m.setWs(StrUtil.blankToDefault(ws, "default"));
        m.setGravAssetId(gravAssetId);
        m.setColumnName(MASK_COL);
        m.setSensitivity("机密");
        m.setMaskAlgo(MASK_ALGO);
        m.setRemark("compliance restrict force mask");
        m.setDeleteFlag(NOT_DELETE);
        m.setCreateTime(now);
        m.setUpdateTime(now);
        maskMapper.insert(m);
        return true;
    }

    private static String bare(String fqn) {
        if (StrUtil.isBlank(fqn)) {
            return "";
        }
        String t = fqn.trim();
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }
}
