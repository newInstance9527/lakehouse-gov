package vip.xiaonuo.lh.modular.sec.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.auth.core.util.StpLoginUserUtil;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicketItem;
import vip.xiaonuo.lh.modular.apply.mapper.ApplyTicketMapper;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.sec.entity.LhTrinoPrincipal;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.service.GravTableAccessService;
import vip.xiaonuo.lh.modular.sec.service.LhTrinoPrincipalService;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class GravTableAccessServiceImpl implements GravTableAccessService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String TYPE_TABLE_READ = "table_read";

    @Resource
    private LhTrinoPrincipalService principalService;
    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private TrinoClient trinoClient;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private ApplyTicketMapper ticketMapper;
    @Resource
    private SecAuthGrantService secAuthGrantService;

    @Override
    public Map<String, Object> grantTableRead(ApplyTicket ticket, ApplyTicketItem item,
                                              String privilege, String rowFilter) {
        if (ticket == null || item == null) {
            throw new CommonException("表读申请不完整");
        }
        String priv = StrUtil.blankToDefault(privilege, "SELECT").trim().toUpperCase(Locale.ROOT);
        if (!"SELECT".equals(priv)) {
            throw new CommonException("表数据权限只接受 SELECT，操作权请走 resource_manage");
        }
        LhTrinoPrincipal principal = principalService.requireByPortalUserId(ticket.getApplicant());
        GovAsset asset = assetMapper.selectById(StrUtil.blankToDefault(item.getAssetId(), ""));
        if (asset == null) {
            throw new CommonException("资产不存在，无法写入 Gravitino");
        }
        CbGravAssetRef ref = resolveRef(asset);
        if (ref == null) {
            throw new CommonException("资产未挂接 Gravitino，拒绝只写门户授权");
        }
        String condition = StrUtil.trim(rowFilter);
        Map<String, Object> granted = gravitinoClient.grantTablePrivilege(
                ref.getGravMetalake(),
                ref.getGravCatalog(),
                ref.getGravSchema(),
                ref.getGravTable(),
                principal.getTrinoUser(),
                priv,
                condition);
        boolean gravOk = Boolean.TRUE.equals(granted.get("ok"));
        if (!gravOk) {
            // 与申请中心约定：ACL soft-fail，门户投影仍写入
            log.warn("Gravitino ACL soft-fail asset={} user={} msg={}",
                    asset.getId(), principal.getTrinoUser(), granted.get("message"));
        }
        String policyId = granted.get("policyId") == null ? null : String.valueOf(granted.get("policyId"));
        SecAuthGrant projection = secAuthGrantService.recordSelectProjection(
                ticket.getId(),
                ticket.getApplicant(),
                asset.getId(),
                ref.getId(),
                ticket.getExpiresAt(),
                condition,
                policyId,
                ticket.getWs(),
                gravOk ? "SELECT projection; Grav ACL ok"
                        : "SELECT projection; Grav ACL soft-fail: " + granted.get("message"));
        JSONObject payload = JSONUtil.parseObj(StrUtil.blankToDefault(ticket.getPayload(), "{}"));
        JSONObject grav = new JSONObject();
        grav.set("principal", principal.getTrinoUser());
        grav.set("privilege", priv);
        grav.set("gravPrivilege", granted.get("privilege"));
        grav.set("role", granted.get("role"));
        grav.set("policyId", granted.get("policyId"));
        grav.set("fullName", ref.getGravCatalog() + "." + ref.getGravSchema() + "." + ref.getGravTable());
        grav.set("rowFilter", StrUtil.blankToDefault(condition, ""));
        grav.set("expiresAt", ticket.getExpiresAt());
        grav.set("revoked", false);
        grav.set("projected", gravOk);
        if (!gravOk) {
            grav.set("error", String.valueOf(granted.get("message")));
        }
        payload.set("grav", grav);
        payload.set("aclStore", gravOk ? "gravitino" : "portal_only");
        ticket.setPayload(payload.toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aclStore", gravOk ? "gravitino" : "portal_only");
        out.put("grantId", projection.getId());
        out.put("trinoUser", principal.getTrinoUser());
        out.put("gravPolicyId", granted.get("policyId"));
        out.put("gravProjected", gravOk);
        out.put("gravRole", granted.get("role"));
        if (!gravOk) {
            out.put("gravMessage", granted.get("message"));
        }
        out.put("rowFilter", StrUtil.blankToDefault(condition, null));
        out.put("expiresAt", ticket.getExpiresAt());
        return out;
    }

    @Override
    public boolean canCurrentSelect(String assetId) {
        SaBaseLoginUser login;
        try {
            login = StpLoginUserUtil.getLoginUser();
        } catch (Exception e) {
            return false;
        }
        if (login == null || StrUtil.isBlank(login.getId())) {
            return false;
        }
        LhTrinoPrincipal principal = principalService.findActive(login.getId());
        if (principal == null) {
            return false;
        }
        GovAsset asset = assetMapper.selectById(assetId);
        CbGravAssetRef ref = asset == null ? null : resolveRef(asset);
        if (ref == null) {
            return false;
        }
        String sql = "SELECT 1 FROM " + q(ref.getGravCatalog()) + "." + q(ref.getGravSchema())
                + "." + q(ref.getGravTable()) + " LIMIT 0";
        try {
            Map<String, Object> exec = trinoClient.execute(sql,
                    TrinoClient.ExecuteOptions.human(principal.getTrinoUser(), 1));
            return !Boolean.TRUE.equals(exec.get("degraded"));
        } catch (CommonException e) {
            return false;
        } catch (Exception e) {
            log.debug("grav select probe failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public int revokeExpired() {
        Date now = new Date();
        List<ApplyTicket> due = ticketMapper.selectList(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getStatus, "approved")
                .eq(ApplyTicket::getTicketType, TYPE_TABLE_READ)
                .isNotNull(ApplyTicket::getExpiresAt)
                .le(ApplyTicket::getExpiresAt, now));
        int n = 0;
        for (ApplyTicket ticket : due) {
            JSONObject payload = JSONUtil.parseObj(StrUtil.blankToDefault(ticket.getPayload(), "{}"));
            JSONObject grav = payload.getJSONObject("grav");
            if (grav == null || Boolean.TRUE.equals(grav.getBool("revoked"))) {
                continue;
            }
            String principal = grav.getStr("principal");
            String fullName = grav.getStr("fullName");
            if (StrUtil.hasBlank(principal, fullName)) {
                continue;
            }
            String[] parts = fullName.split("\\.");
            if (parts.length != 3) {
                continue;
            }
            GovAsset asset = assetMapper.selectById(jsonAssetId(payload));
            String metalake = asset == null ? null : metalakeOf(asset);
            if (StrUtil.isBlank(metalake)) {
                log.warn("skip grav revoke, metalake missing ticket={}", ticket.getTicketNo());
                continue;
            }
            Map<String, Object> revoked = gravitinoClient.revokeTablePrivilege(
                    metalake, parts[0], parts[1], parts[2], principal,
                    StrUtil.blankToDefault(grav.getStr("privilege"), "SELECT"));
            if (!Boolean.TRUE.equals(revoked.get("ok"))) {
                log.warn("grav revoke retry later ticket={} msg={}", ticket.getTicketNo(), revoked.get("message"));
                continue;
            }
            grav.set("revoked", true);
            grav.set("revokedAt", now);
            payload.set("grav", grav);
            ticket.setPayload(payload.toString());
            ticket.setUpdateTime(now);
            ticketMapper.updateById(ticket);
            secAuthGrantService.expireSelectProjection(ticket.getId());
            n++;
        }
        return n;
    }

    private CbGravAssetRef resolveRef(GovAsset asset) {
        if (asset == null || StrUtil.isBlank(asset.getGravAssetId())) {
            return null;
        }
        return gravAssetRefMapper.selectById(asset.getGravAssetId());
    }

    private String metalakeOf(GovAsset asset) {
        CbGravAssetRef ref = resolveRef(asset);
        return ref == null ? null : ref.getGravMetalake();
    }

    private static String jsonAssetId(JSONObject payload) {
        return StrUtil.blankToDefault(payload.getStr("assetId"), "");
    }

    private static String q(String ident) {
        if (StrUtil.isBlank(ident) || ident.indexOf('"') >= 0 || ident.indexOf(';') >= 0) {
            throw new CommonException("非法表标识");
        }
        return "\"" + ident + "\"";
    }
}
