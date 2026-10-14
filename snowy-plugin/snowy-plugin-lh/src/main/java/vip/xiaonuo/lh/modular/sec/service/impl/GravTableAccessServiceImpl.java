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
        // 前端 permMode 可能是 read/column/plain；引擎表级一律 SELECT
        String mode = StrUtil.blankToDefault(privilege, "SELECT").trim();
        String priv = "SELECT";
        if (!"SELECT".equalsIgnoreCase(mode)
                && !"READ".equalsIgnoreCase(mode)
                && !"COLUMN".equalsIgnoreCase(mode)
                && !"PLAIN".equalsIgnoreCase(mode)) {
            throw new CommonException("表数据权限只接受 SELECT/column/plain，操作权请走 resource_manage");
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
        if (StrUtil.isNotBlank(condition)
                && !vip.xiaonuo.lh.modular.query.support.CpQueryRowFilterInjector.isSafePredicate(condition)) {
            throw new CommonException("rowFilter 不安全，拒绝授权（禁止多语句/DDL/注释）");
        }
        JSONObject ticketPayload = JSONUtil.parseObj(StrUtil.blankToDefault(ticket.getPayload(), "{}"));
        String allowedColumns = SecAuthGrantServiceImpl.normalizeAllowedColumns(ticketPayload.getStr("columns"));
        String privilegeMode = StrUtil.blankToDefault(ticketPayload.getStr("privilege"), mode).trim();
        String columnMask = null;
        // plain = 敏感列明文：列白名单 + column_mask.plain 豁免打标
        if (StrUtil.isNotBlank(allowedColumns) && "plain".equalsIgnoreCase(privilegeMode)) {
            JSONObject mask = new JSONObject();
            mask.set("plain", List.of(allowedColumns.split(",")));
            columnMask = mask.toString();
        }
        // Grav condition 仅为 ALLOW/DENY，不承载行过滤；传 null 避免误写引擎
        Map<String, Object> granted = gravitinoClient.grantTablePrivilege(
                ref.getGravMetalake(),
                ref.getGravCatalog(),
                ref.getGravSchema(),
                ref.getGravTable(),
                principal.getTrinoUser(),
                priv,
                null);
        boolean gravOk = Boolean.TRUE.equals(granted.get("ok"));
        if (!gravOk) {
            // 引擎失败不写门户 SELECT 投影，避免目录/元数据假授权
            log.warn("Gravitino ACL soft-fail asset={} user={} msg={}",
                    asset.getId(), principal.getTrinoUser(), granted.get("message"));
        }
        String policyId = granted.get("policyId") == null ? null : String.valueOf(granted.get("policyId"));
        SecAuthGrant projection = null;
        if (gravOk) {
            projection = secAuthGrantService.recordSelectProjection(
                    ticket.getId(),
                    ticket.getApplicant(),
                    asset.getId(),
                    ref.getId(),
                    ticket.getExpiresAt(),
                    condition,
                    allowedColumns,
                    columnMask,
                    policyId,
                    ticket.getWs(),
                    "SELECT projection; Grav ACL ok");
        }
        JSONObject payload = ticketPayload;
        JSONObject grav = new JSONObject();
        grav.set("principal", principal.getTrinoUser());
        grav.set("privilege", priv);
        grav.set("gravPrivilege", granted.get("privilege"));
        grav.set("role", granted.get("role"));
        grav.set("policyId", granted.get("policyId"));
        grav.set("fullName", ref.getGravCatalog() + "." + ref.getGravSchema() + "." + ref.getGravTable());
        grav.set("rowFilter", StrUtil.blankToDefault(condition, ""));
        grav.set("allowedColumns", StrUtil.blankToDefault(allowedColumns, ""));
        grav.set("expiresAt", ticket.getExpiresAt());
        grav.set("revoked", false);
        grav.set("projected", gravOk);
        if (!gravOk) {
            grav.set("error", String.valueOf(granted.get("message")));
            grav.set("softFail", true);
        }
        payload.set("grav", grav);
        payload.set("aclStore", gravOk ? "gravitino" : "grav_soft_fail");
        ticket.setPayload(payload.toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aclStore", gravOk ? "gravitino" : "grav_soft_fail");
        out.put("grantId", projection == null ? null : projection.getId());
        out.put("trinoUser", principal.getTrinoUser());
        out.put("gravPolicyId", granted.get("policyId"));
        out.put("gravProjected", gravOk);
        out.put("gravRole", granted.get("role"));
        out.put("softFail", !gravOk);
        if (!gravOk) {
            out.put("gravMessage", granted.get("message"));
        }
        out.put("rowFilter", StrUtil.blankToDefault(condition, null));
        out.put("allowedColumns", allowedColumns);
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
