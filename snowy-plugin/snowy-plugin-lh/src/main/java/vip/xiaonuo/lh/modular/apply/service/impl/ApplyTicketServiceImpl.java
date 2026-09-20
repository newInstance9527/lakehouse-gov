package vip.xiaonuo.lh.modular.apply.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicketItem;
import vip.xiaonuo.lh.modular.apply.mapper.ApplyTicketItemMapper;
import vip.xiaonuo.lh.modular.apply.mapper.ApplyTicketMapper;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketCreateParam;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketDecideParam;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketPageParam;
import vip.xiaonuo.lh.modular.apply.service.ApplyTicketService;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class ApplyTicketServiceImpl implements ApplyTicketService {

    private static final String NOT_DELETE = "NOT_DELETE";
    public static final String TYPE_TABLE_READ = "table_read";
    public static final String TYPE_LAKE_EXPORT = "lake_export";
    public static final String TYPE_RESOURCE_MANAGE = "resource_manage";

    @Resource
    private ApplyTicketMapper ticketMapper;
    @Resource
    private ApplyTicketItemMapper itemMapper;
    @Resource
    private GovAssetMapper govAssetMapper;
    @Resource
    private SecAuthGrantService secAuthGrantService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApplyTicket create(ApplyTicketCreateParam param) {
        String userId = LhLoginUsers.requireUserId();
        String type = normalizeTicketType(param.getTicketType());
        if (TYPE_LAKE_EXPORT.equals(type)) {
            return createExport(param, userId);
        }
        if (TYPE_RESOURCE_MANAGE.equals(type)) {
            return createResourceManage(param, userId);
        }
        return createTableRead(param, userId, type);
    }

    private ApplyTicket createResourceManage(ApplyTicketCreateParam param, String userId) {
        vip.xiaonuo.lh.modular.sec.enums.LhOpsResourceTypeEnum typeEnum =
                vip.xiaonuo.lh.modular.sec.enums.LhOpsResourceTypeEnum.requireEnabled(
                        StrUtil.blankToDefault(param.getResourceType(), "asset"));
        String resourceType = typeEnum.getValue();
        String resourceId = StrUtil.blankToDefault(param.getResourceId(), param.getAssetId()).trim();
        if (StrUtil.isBlank(resourceId)) {
            throw new CommonException("操作权限申请须指定 resourceId（或 assetId）");
        }
        String ws = "default";
        String gravAssetId = null;
        String omFqn = null;
        String assetId = null;
        String resourceName = StrUtil.blankToDefault(param.getTitle(), resourceId);
        if (typeEnum == vip.xiaonuo.lh.modular.sec.enums.LhOpsResourceTypeEnum.ASSET) {
            GovAsset asset = resolveAsset(resourceId);
            assetId = asset.getId();
            resourceId = asset.getId();
            ws = asset.getWs();
            gravAssetId = asset.getGravAssetId();
            omFqn = asset.getOmFqn();
            resourceName = StrUtil.blankToDefault(asset.getAssetCode(), asset.getName());
        }
        String privilege = vip.xiaonuo.lh.modular.sec.enums.LhOpsPrivilegeEnum
                .requireOps(StrUtil.blankToDefault(param.getPrivilege(), "MANAGE"))
                .getValue();
        ApplyTicket t = newTicketShell(userId, TYPE_RESOURCE_MANAGE, param, ws);
        t.setTicketNo("OP" + t.getId());
        JSONObject payload = new JSONObject();
        payload.set("resourceType", resourceType);
        payload.set("resourceId", resourceId);
        payload.set("resourceName", resourceName);
        if (assetId != null) {
            payload.set("assetId", assetId);
        }
        payload.set("privilege", privilege);
        payload.set("expireLabel", param.getExpireLabel());
        t.setPayload(payload.toString());
        t.setExpiresAt(parseExpire(param.getExpireLabel()));
        ticketMapper.insert(t);

        ApplyTicketItem item = newItemShell(userId, t.getId());
        item.setAssetId(assetId);
        item.setGravAssetId(gravAssetId);
        item.setOmFqn(omFqn);
        item.setAction(privilege);
        item.setDetail(payload.toString());
        itemMapper.insert(item);
        return t;
    }

    private ApplyTicket createTableRead(ApplyTicketCreateParam param, String userId, String type) {
        if (StrUtil.isBlank(param.getAssetId())) {
            throw new CommonException("表读权限申请须指定 assetId");
        }
        GovAsset asset = resolveAsset(param.getAssetId());
        ApplyTicket t = newTicketShell(userId, type, param, asset.getWs());
        t.setTicketNo("AT" + t.getId());
        JSONObject payload = new JSONObject();
        payload.set("assetId", asset.getId());
        payload.set("assetCode", asset.getAssetCode());
        payload.set("privilege", StrUtil.blankToDefault(param.getPrivilege(), "SELECT"));
        payload.set("expireLabel", param.getExpireLabel());
        payload.set("columns", param.getColumns());
        t.setPayload(payload.toString());
        t.setExpiresAt(parseExpire(param.getExpireLabel()));
        ticketMapper.insert(t);

        ApplyTicketItem item = newItemShell(userId, t.getId());
        item.setAssetId(asset.getId());
        item.setGravAssetId(asset.getGravAssetId());
        item.setOmFqn(asset.getOmFqn());
        item.setAction(StrUtil.blankToDefault(param.getPrivilege(), "SELECT"));
        item.setDetail(payload.toString());
        itemMapper.insert(item);
        return t;
    }

    private ApplyTicket createExport(ApplyTicketCreateParam param, String userId) {
        String exportTable = StrUtil.trim(param.getExportTable());
        String exportTarget = StrUtil.trim(param.getExportTarget());
        if (StrUtil.isBlank(exportTable)) {
            throw new CommonException("出湖申请须填写 exportTable（源表）");
        }
        if (StrUtil.isBlank(exportTarget)) {
            throw new CommonException("出湖申请须填写 exportTarget（目标）");
        }
        GovAsset asset = null;
        if (StrUtil.isNotBlank(param.getAssetId())) {
            try {
                asset = resolveAsset(param.getAssetId());
            } catch (CommonException ignored) {
                // 出湖允许仅表名，无资产锚点
            }
        }
        if (asset == null) {
            asset = findAssetByCodeOrName(exportTable);
        }
        String ws = asset != null ? StrUtil.blankToDefault(asset.getWs(), "default") : "default";
        ApplyTicket t = newTicketShell(userId, TYPE_LAKE_EXPORT, param, ws);
        t.setTicketNo(nextExportTicketNo());
        JSONObject payload = new JSONObject();
        payload.set("exportTable", exportTable);
        payload.set("exportTarget", exportTarget);
        payload.set("expireLabel", param.getExpireLabel());
        payload.set("purpose", param.getReason());
        if (asset != null) {
            payload.set("assetId", asset.getId());
            payload.set("assetCode", asset.getAssetCode());
        }
        t.setPayload(payload.toString());
        t.setExpiresAt(parseExpire(param.getExpireLabel()));
        if (StrUtil.isBlank(t.getTitle())) {
            t.setTitle("出湖 · " + exportTable + " → " + exportTarget);
        }
        ticketMapper.insert(t);

        ApplyTicketItem item = newItemShell(userId, t.getId());
        if (asset != null) {
            item.setAssetId(asset.getId());
            item.setGravAssetId(asset.getGravAssetId());
            item.setOmFqn(asset.getOmFqn());
        }
        item.setAction("EXPORT");
        item.setDetail(payload.toString());
        itemMapper.insert(item);
        return t;
    }

    @Override
    public Page<ApplyTicket> pageMine(ApplyTicketPageParam param) {
        String userId = LhLoginUsers.requireUserId();
        long current = param.getCurrent() == null ? 1L : param.getCurrent();
        long size = param.getSize() == null ? 20L : param.getSize();
        QueryWrapper<ApplyTicket> qw = new QueryWrapper<>();
        qw.lambda().eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getApplicant, userId)
                .eq(StrUtil.isNotBlank(param.getStatus()), ApplyTicket::getStatus, param.getStatus())
                .eq(StrUtil.isNotBlank(normalizeFilterType(param.getTicketType())),
                        ApplyTicket::getTicketType, normalizeFilterType(param.getTicketType()))
                .orderByDesc(ApplyTicket::getCreateTime);
        return ticketMapper.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Page<ApplyTicket> pagePending(ApplyTicketPageParam param) {
        if (!LhLoginUsers.isSuperAdmin()) {
            throw new CommonException("仅超管可查看待审批列表（一期）");
        }
        long current = param.getCurrent() == null ? 1L : param.getCurrent();
        long size = param.getSize() == null ? 20L : param.getSize();
        QueryWrapper<ApplyTicket> qw = new QueryWrapper<>();
        qw.lambda().eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getStatus, "pending")
                .eq(StrUtil.isNotBlank(normalizeFilterType(param.getTicketType())),
                        ApplyTicket::getTicketType, normalizeFilterType(param.getTicketType()))
                .orderByAsc(ApplyTicket::getCreateTime);
        return ticketMapper.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> approve(ApplyTicketDecideParam param) {
        if (!LhLoginUsers.isSuperAdmin()) {
            throw new CommonException("仅超管可审批（一期）");
        }
        ApplyTicket t = requireTicket(param.getId());
        if (!"pending".equals(t.getStatus())) {
            throw new CommonException("申请单状态不可审批: " + t.getStatus());
        }
        ApplyTicketItem item = itemMapper.selectOne(new QueryWrapper<ApplyTicketItem>().lambda()
                .eq(ApplyTicketItem::getTicketId, t.getId())
                .eq(ApplyTicketItem::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (item == null) {
            throw new CommonException("申请明细缺失");
        }

        Map<String, Object> r = new LinkedHashMap<>();
        if (TYPE_LAKE_EXPORT.equals(t.getTicketType())) {
            t.setStatus("approved");
            t.setApprovedBy(LhLoginUsers.requireUserId());
            t.setApprovedAt(new Date());
            t.setRemark(param.getRemark());
            t.setUpdateTime(new Date());
            t.setUpdateUser(LhLoginUsers.requireUserId());
            ticketMapper.updateById(t);
            r.put("ticket", t);
            r.put("ticketNo", t.getTicketNo());
            r.put("grantId", null);
            r.put("gravProjected", false);
            return r;
        }

        JSONObject payload = JSONUtil.parseObj(StrUtil.blankToDefault(t.getPayload(), "{}"));
        String privilege = item.getAction();
        if (StrUtil.isNotBlank(payload.getStr("privilege"))) {
            privilege = payload.getStr("privilege");
        }
        if (TYPE_RESOURCE_MANAGE.equals(t.getTicketType())) {
            privilege = vip.xiaonuo.lh.modular.sec.enums.LhOpsPrivilegeEnum
                    .requireOps(StrUtil.blankToDefault(privilege, "MANAGE"))
                    .getValue();
        }
        String resourceType = StrUtil.blankToDefault(payload.getStr("resourceType"),
                StrUtil.isNotBlank(item.getAssetId()) ? "asset" : "");
        String resourceId = StrUtil.blankToDefault(payload.getStr("resourceId"), item.getAssetId());
        if (TYPE_RESOURCE_MANAGE.equals(t.getTicketType()) && StrUtil.isBlank(resourceId)) {
            throw new CommonException("操作权限申请缺少 resourceId");
        }
        SecAuthGrant grant = secAuthGrantService.createFromApproval(
                t.getId(),
                t.getApplicant(),
                TYPE_RESOURCE_MANAGE.equals(t.getTicketType()) ? resourceType : "asset",
                TYPE_RESOURCE_MANAGE.equals(t.getTicketType()) ? resourceId : item.getAssetId(),
                item.getAssetId(),
                item.getGravAssetId(),
                privilege,
                t.getExpiresAt(),
                param.getRemark());
        item.setResultGrantId(grant.getId());
        item.setUpdateTime(new Date());
        itemMapper.updateById(item);

        t.setStatus("approved");
        t.setApprovedBy(LhLoginUsers.requireUserId());
        t.setApprovedAt(new Date());
        t.setRemark(param.getRemark());
        t.setUpdateTime(new Date());
        t.setUpdateUser(LhLoginUsers.requireUserId());
        ticketMapper.updateById(t);

        r.put("ticket", t);
        r.put("ticketNo", t.getTicketNo());
        r.put("grantId", grant.getId());
        r.put("gravProjected", grant.getGravProjected());
        r.put("gravPolicyId", grant.getGravPolicyId());
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApplyTicket reject(ApplyTicketDecideParam param) {
        if (!LhLoginUsers.isSuperAdmin()) {
            throw new CommonException("仅超管可审批（一期）");
        }
        ApplyTicket t = requireTicket(param.getId());
        if (!"pending".equals(t.getStatus())) {
            throw new CommonException("申请单状态不可驳回: " + t.getStatus());
        }
        t.setStatus("rejected");
        t.setApprovedBy(LhLoginUsers.requireUserId());
        t.setApprovedAt(new Date());
        t.setRemark(param.getRemark());
        t.setUpdateTime(new Date());
        t.setUpdateUser(LhLoginUsers.requireUserId());
        ticketMapper.updateById(t);
        return t;
    }

    @Override
    public void assertApprovedExportTicket(String ticketNo) {
        String no = StrUtil.trim(ticketNo);
        if (StrUtil.isBlank(no)) {
            throw new CommonException("出湖须填写申请单号 ticketNo");
        }
        ApplyTicket t = ticketMapper.selectOne(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getTicketNo, no)
                .last("LIMIT 1"));
        if (t == null) {
            throw new CommonException("出湖申请单不存在: " + no + "（请先到申请中心提交出湖申请）");
        }
        if (!TYPE_LAKE_EXPORT.equals(t.getTicketType())) {
            throw new CommonException("单号 " + no + " 不是出湖申请（ticket_type=" + t.getTicketType() + "）");
        }
        if (!"approved".equals(t.getStatus())) {
            throw new CommonException("出湖申请尚未通过审批: " + no + "（status=" + t.getStatus() + "）");
        }
        if (t.getExpiresAt() != null && t.getExpiresAt().before(new Date())) {
            throw new CommonException("出湖申请已过期: " + no);
        }
    }

    private ApplyTicket newTicketShell(String userId, String type, ApplyTicketCreateParam param, String ws) {
        ApplyTicket t = new ApplyTicket();
        t.setId(IdUtil.getSnowflakeNextIdStr());
        t.setRevision(1);
        t.setStatus("pending");
        t.setWs(StrUtil.blankToDefault(ws, "default"));
        t.setTicketType(type);
        t.setTitle(param.getTitle());
        t.setApplicant(userId);
        t.setReason(param.getReason());
        t.setDeleteFlag(NOT_DELETE);
        t.setCreateTime(new Date());
        t.setCreateUser(userId);
        return t;
    }

    private ApplyTicketItem newItemShell(String userId, String ticketId) {
        ApplyTicketItem item = new ApplyTicketItem();
        item.setId(IdUtil.getSnowflakeNextIdStr());
        item.setRevision(1);
        item.setTicketId(ticketId);
        item.setDeleteFlag(NOT_DELETE);
        item.setCreateTime(new Date());
        item.setCreateUser(userId);
        return item;
    }

    private synchronized String nextExportTicketNo() {
        // EXP- + 雪花末 9 位；冲突极少，若撞 uq 则再生成
        for (int i = 0; i < 5; i++) {
            String id = IdUtil.getSnowflakeNextIdStr();
            String suffix = id.length() <= 9 ? id : id.substring(id.length() - 9);
            String no = "EXP-" + suffix;
            Long cnt = ticketMapper.selectCount(new QueryWrapper<ApplyTicket>().lambda()
                    .eq(ApplyTicket::getTicketNo, no));
            if (cnt == null || cnt == 0) {
                return no;
            }
        }
        return "EXP-" + IdUtil.getSnowflakeNextIdStr();
    }

    /**
     * 前端 tab：perm→table_read，export→lake_export，manage→resource_manage
     */
    static String normalizeTicketType(String raw) {
        String t = StrUtil.blankToDefault(raw, TYPE_TABLE_READ).trim().toLowerCase(Locale.ROOT);
        if ("perm".equals(t) || "permission".equals(t) || "table_read".equals(t)) {
            return TYPE_TABLE_READ;
        }
        if ("export".equals(t) || "lake_export".equals(t) || "outbound".equals(t)) {
            return TYPE_LAKE_EXPORT;
        }
        if ("manage".equals(t) || "resource_manage".equals(t) || "owner".equals(t)) {
            return TYPE_RESOURCE_MANAGE;
        }
        return t;
    }

    private static String normalizeFilterType(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        return normalizeTicketType(raw);
    }

    private ApplyTicket requireTicket(String id) {
        ApplyTicket t = ticketMapper.selectById(id);
        if (t == null || !NOT_DELETE.equals(t.getDeleteFlag())) {
            // 也允许用 ticketNo 审批
            if (StrUtil.isNotBlank(id)) {
                t = ticketMapper.selectOne(new QueryWrapper<ApplyTicket>().lambda()
                        .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                        .eq(ApplyTicket::getTicketNo, id)
                        .last("LIMIT 1"));
            }
        }
        if (t == null || !NOT_DELETE.equals(t.getDeleteFlag())) {
            throw new CommonException("申请单不存在");
        }
        return t;
    }

    private GovAsset resolveAsset(String idOrCode) {
        GovAsset byId = govAssetMapper.selectById(idOrCode);
        if (byId != null && NOT_DELETE.equals(byId.getDeleteFlag())) {
            return byId;
        }
        GovAsset byCode = findAssetByCodeOrName(idOrCode);
        if (byCode == null) {
            throw new CommonException("资产不存在: " + idOrCode);
        }
        return byCode;
    }

    private GovAsset findAssetByCodeOrName(String codeOrName) {
        if (StrUtil.isBlank(codeOrName)) {
            return null;
        }
        GovAsset byCode = govAssetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(GovAsset::getAssetCode, codeOrName)
                .last("LIMIT 1"));
        if (byCode != null) {
            return byCode;
        }
        return govAssetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(GovAsset::getName, codeOrName)
                .last("LIMIT 1"));
    }

    private Date parseExpire(String label) {
        if (StrUtil.isBlank(label) || "长期".equals(label.trim())) {
            return null;
        }
        Calendar cal = Calendar.getInstance();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*天").matcher(label);
        if (m.find()) {
            cal.add(Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(1)));
            return cal.getTime();
        }
        if (label.contains("7")) {
            cal.add(Calendar.DAY_OF_MONTH, 7);
        } else if (label.contains("90")) {
            cal.add(Calendar.DAY_OF_MONTH, 90);
        } else {
            cal.add(Calendar.DAY_OF_MONTH, 30);
        }
        return cal.getTime();
    }
}
