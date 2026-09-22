package vip.xiaonuo.lh.modular.apply.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.auth.LhOwnerGuard;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicketItem;
import vip.xiaonuo.lh.modular.apply.mapper.ApplyTicketItemMapper;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.workspace.entity.GovWs;
import vip.xiaonuo.lh.modular.workspace.entity.GovWsMember;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsMapper;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsMemberMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * G1 + J2：审批候选人随步骤变化。
 * <ul>
 *   <li>{@code pending}（含单级 / 多级 Owner 步）：超管 | 资产 Owner | 空间 Owner</li>
 *   <li>{@code pending_security}（机密明文安全加签）：超管 | 空间 SecurityOfficer</li>
 * </ul>
 * 授权结果仍只写 grant/Grav，不写回空间成员 ACL。
 */
@Service
public class ApplyApprovalCandidateService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String STATUS_ACTIVE = "active";
    private static final String ROLE_SECURITY = "SecurityOfficer";

    @Resource
    private GovWsMemberMapper memberMapper;
    @Resource
    private GovWsMapper wsMapper;
    @Resource
    private GovAssetMapper govAssetMapper;
    @Resource
    private ApplyTicketItemMapper itemMapper;

    public void assertCanDecide(ApplyTicket ticket) {
        if (canDecide(ticket)) {
            return;
        }
        String step = ApplyApprovalChain.currentStep(
                ticket == null ? null : ticket.getStatus(),
                ticket == null ? null : ApplyApprovalChain.parsePayload(ticket.getPayload()));
        if (ApplyApprovalChain.STEP_SECURITY.equals(step)) {
            throw new CommonException("仅空间安全岗（SecurityOfficer）或超管可加签本单");
        }
        throw new CommonException("仅资产 Owner、空间 Owner 或超管可审批本单");
    }

    public boolean canDecide(ApplyTicket ticket) {
        if (ticket == null || !ApplyApprovalChain.isOpenForDecision(ticket.getStatus())) {
            return false;
        }
        if (LhLoginUsers.isSuperAdmin()) {
            return true;
        }
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        JSONObject payload = ApplyApprovalChain.parsePayload(ticket.getPayload());
        String step = ApplyApprovalChain.currentStep(ticket.getStatus(), payload);

        if (ApplyApprovalChain.STEP_SECURITY.equals(step)) {
            return isSecurityOfficerOfWs(user, ticket.getWs());
        }
        // Owner 步（单级或机密多级首步）
        Set<String> ownedWs = resolveOwnedWsCodes(user);
        if (ownedWs.contains(normWs(ticket.getWs()))) {
            return true;
        }
        String assetId = resolveLinkedAssetId(ticket.getId());
        if (StrUtil.isBlank(assetId)) {
            return false;
        }
        GovAsset asset = govAssetMapper.selectById(assetId);
        return isAssetOwner(user, asset);
    }

    /**
     * 批量判定：用于 pending 列表过滤，避免逐单查库。
     */
    public List<ApplyTicket> filterDecidable(List<ApplyTicket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return List.of();
        }
        if (LhLoginUsers.isSuperAdmin()) {
            return List.copyOf(tickets);
        }
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        Set<String> ownedWs = resolveOwnedWsCodes(user);
        Set<String> securityWs = resolveSecurityWsCodes(user);
        Map<String, String> ticketAssetIds = loadTicketAssetIds(
                tickets.stream().map(ApplyTicket::getId).collect(Collectors.toList()));
        Set<String> assetIds = ticketAssetIds.values().stream()
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        Map<String, GovAsset> assets = loadAssets(assetIds);

        return tickets.stream()
                .filter(t -> {
                    JSONObject payload = ApplyApprovalChain.parsePayload(t.getPayload());
                    String step = ApplyApprovalChain.currentStep(t.getStatus(), payload);
                    if (ApplyApprovalChain.STEP_SECURITY.equals(step)) {
                        return securityWs.contains(normWs(t.getWs()));
                    }
                    if (ownedWs.contains(normWs(t.getWs()))) {
                        return true;
                    }
                    String assetId = ticketAssetIds.get(t.getId());
                    return isAssetOwner(user, assets.get(assetId));
                })
                .collect(Collectors.toList());
    }

    public long countDecidablePending(List<ApplyTicket> pendingTickets) {
        return filterDecidable(pendingTickets).size();
    }

    Set<String> resolveOwnedWsCodes(SaBaseLoginUser user) {
        return resolveWsCodesByRole(user, "Owner", true);
    }

    Set<String> resolveSecurityWsCodes(SaBaseLoginUser user) {
        return resolveWsCodesByRole(user, ROLE_SECURITY, false);
    }

    private Set<String> resolveWsCodesByRole(SaBaseLoginUser user, String roleCode, boolean includeCreatorAsOwner) {
        if (user == null) {
            return Set.of();
        }
        Set<String> codes = new HashSet<>();
        String uid = StrUtil.trim(user.getId());
        String account = StrUtil.trim(user.getAccount());
        List<GovWsMember> members = memberMapper.selectList(new QueryWrapper<GovWsMember>().lambda()
                .eq(GovWsMember::getDeleteFlag, NOT_DELETE)
                .eq(GovWsMember::getStatus, STATUS_ACTIVE)
                .eq(GovWsMember::getSubjectType, "user")
                .eq(GovWsMember::getRoleCode, roleCode)
                .and(w -> {
                    if (StrUtil.isNotBlank(uid) && StrUtil.isNotBlank(account)) {
                        w.eq(GovWsMember::getSubjectId, uid).or().eq(GovWsMember::getSubjectId, account);
                    } else if (StrUtil.isNotBlank(uid)) {
                        w.eq(GovWsMember::getSubjectId, uid);
                    } else if (StrUtil.isNotBlank(account)) {
                        w.eq(GovWsMember::getSubjectId, account);
                    } else {
                        w.eq(GovWsMember::getSubjectId, "__none__");
                    }
                }));
        for (GovWsMember m : members) {
            if (StrUtil.isNotBlank(m.getWsCode())) {
                codes.add(normWs(m.getWsCode()));
            }
        }
        if (includeCreatorAsOwner) {
            List<GovWs> spaces = wsMapper.selectList(new QueryWrapper<GovWs>().lambda()
                    .eq(GovWs::getDeleteFlag, NOT_DELETE)
                    .eq(GovWs::getStatus, STATUS_ACTIVE));
            for (GovWs ws : spaces) {
                if (LhOwnerGuard.isOwner(user, ws.getCreateUser()) && StrUtil.isNotBlank(ws.getWsCode())) {
                    codes.add(normWs(ws.getWsCode()));
                }
            }
        }
        return codes;
    }

    private boolean isSecurityOfficerOfWs(SaBaseLoginUser user, String wsCode) {
        return resolveSecurityWsCodes(user).contains(normWs(wsCode));
    }

    private boolean isAssetOwner(SaBaseLoginUser user, GovAsset asset) {
        if (user == null || asset == null) {
            return false;
        }
        return LhOwnerGuard.isOwner(user, asset.getCreateUser(), asset.getTechOwner(), asset.getBizOwner());
    }

    private String resolveLinkedAssetId(String ticketId) {
        if (StrUtil.isBlank(ticketId)) {
            return null;
        }
        ApplyTicketItem item = itemMapper.selectOne(new QueryWrapper<ApplyTicketItem>().lambda()
                .eq(ApplyTicketItem::getTicketId, ticketId)
                .eq(ApplyTicketItem::getDeleteFlag, NOT_DELETE)
                .isNotNull(ApplyTicketItem::getAssetId)
                .ne(ApplyTicketItem::getAssetId, "")
                .last("LIMIT 1"));
        return item == null ? null : item.getAssetId();
    }

    private Map<String, String> loadTicketAssetIds(List<String> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return Map.of();
        }
        List<ApplyTicketItem> items = itemMapper.selectList(new QueryWrapper<ApplyTicketItem>().lambda()
                .eq(ApplyTicketItem::getDeleteFlag, NOT_DELETE)
                .in(ApplyTicketItem::getTicketId, ticketIds)
                .isNotNull(ApplyTicketItem::getAssetId)
                .ne(ApplyTicketItem::getAssetId, ""));
        Map<String, String> map = new HashMap<>();
        for (ApplyTicketItem item : items) {
            map.putIfAbsent(item.getTicketId(), item.getAssetId());
        }
        return map;
    }

    private Map<String, GovAsset> loadAssets(Set<String> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of();
        }
        List<GovAsset> list = govAssetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .in(GovAsset::getId, assetIds));
        if (list == null || list.isEmpty()) {
            return Map.of();
        }
        return list.stream().collect(Collectors.toMap(GovAsset::getId, a -> a, (a, b) -> a));
    }

    private static String normWs(String ws) {
        return StrUtil.blankToDefault(ws, "default").trim().toLowerCase(Locale.ROOT);
    }
}
