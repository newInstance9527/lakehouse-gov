package vip.xiaonuo.lh.modular.apply.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
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
import vip.xiaonuo.lh.modular.apply.support.ApplyApprovalCandidateService;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.compliance.support.GovDelProcessingGate;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiBindingMapper;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiIdParam;
import vip.xiaonuo.lh.modular.dataapi.service.DataapiService;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTransitionParam;
import vip.xiaonuo.lh.modular.metric.result.GovMetricVo;
import vip.xiaonuo.lh.modular.metric.service.GovMetricService;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.service.GravTableAccessService;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ApplyTicketServiceImpl implements ApplyTicketService {

    private static final String NOT_DELETE = "NOT_DELETE";
    public static final String TYPE_TABLE_READ = "table_read";
    public static final String TYPE_LAKE_EXPORT = "lake_export";
    public static final String TYPE_RESOURCE_MANAGE = "resource_manage";
    public static final String TYPE_COMPLIANCE_DELETE = "compliance_delete";
    public static final String TYPE_API_PUBLISH = "api_publish";
    /** 数据服务订阅调用 Key */
    public static final String TYPE_API_SUBSCRIBE = "api_subscribe";
    /** 指标发布 / 变更 / 查询权限 */
    public static final String TYPE_METRIC = "metric";
    /** 即席扫描抬额（硬顶 50GB） */
    public static final String TYPE_SCAN_ELEVATE = "scan_elevate";

    @Resource
    private ApplyTicketMapper ticketMapper;
    @Resource
    private ApplyTicketItemMapper itemMapper;
    @Resource
    private GovAssetMapper govAssetMapper;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private GravTableAccessService gravTableAccessService;
    @Resource
    private vip.xiaonuo.lh.modular.dataapi.service.DataapiKeyIssueService dataapiKeyIssueService;
    @Resource
    private DataapiApiBindingMapper dataapiApiBindingMapper;
    @Resource
    @Lazy
    private DataapiService dataapiService;
    @Resource
    @Lazy
    private GovMetricService govMetricService;
    @Resource
    private GovDelProcessingGate govDelProcessingGate;
    @Resource
    private ApplyApprovalCandidateService approvalCandidateService;

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
        if (TYPE_COMPLIANCE_DELETE.equals(type)) {
            return createComplianceDelete(param, userId);
        }
        if (TYPE_API_PUBLISH.equals(type)) {
            return createApiPublish(param, userId);
        }
        if (TYPE_API_SUBSCRIBE.equals(type)) {
            return createApiSubscribe(param, userId);
        }
        if (TYPE_METRIC.equals(type)) {
            return createMetric(param, userId);
        }
        if (TYPE_SCAN_ELEVATE.equals(type)) {
            return createScanElevate(param, userId);
        }
        return createTableRead(param, userId, type);
    }

    /**
     * 即席扫描抬额：审批通过后写 SCAN_ELEVATE grant，exec elevated=true 才放行硬顶 50GB。
     */
    private ApplyTicket createScanElevate(ApplyTicketCreateParam param, String userId) {
        ApplyTicket t = newTicketShell(userId, TYPE_SCAN_ELEVATE, param, "default");
        t.setTicketNo(nextPrefixedTicketNo("SE-"));
        t.setExpiresAt(parseExpire(param.getExpireLabel()));
        JSONObject payload = new JSONObject();
        payload.set("privilege", "SCAN_ELEVATE");
        payload.set("resourceType", "adhoc");
        payload.set("resourceId", "platform");
        payload.set("scanHardLimitBytes", 50L * 1024 * 1024 * 1024);
        payload.set("expireLabel", param.getExpireLabel());
        t.setPayload(payload.toString());
        if (StrUtil.isBlank(t.getTitle())) {
            t.setTitle("扫描抬额 · 硬顶 50GB");
        }
        if (StrUtil.isBlank(t.getReason())) {
            t.setReason(StrUtil.blankToDefault(param.getReason(), "即席查询需抬升扫描限额至平台硬顶 50GB"));
        }
        ticketMapper.insert(t);
        ApplyTicketItem item = newItemShell(userId, t.getId());
        item.setAssetId(null);
        item.setAction("SCAN_ELEVATE");
        item.setDetail(payload.toString());
        itemMapper.insert(item);
        return t;
    }

    /**
     * 指标申请：create/change = 发布流（对齐 API：草稿→待发布→审批→自动启用）；query = 查询权限。
     */
    private ApplyTicket createMetric(ApplyTicketCreateParam param, String userId) {
        String metricCode = StrUtil.trim(param.getMetricCode());
        String metricKind = StrUtil.blankToDefault(param.getMetricKind(), "query").trim().toLowerCase(Locale.ROOT);
        if (!"create".equals(metricKind) && !"change".equals(metricKind) && !"query".equals(metricKind)) {
            throw new CommonException("metricKind 须为 create / change / query");
        }
        if (StrUtil.isBlank(metricCode)) {
            throw new CommonException("指标申请须携带 metricCode（请先在指标中心保存草稿）");
        }
        GovMetricVo metric = govMetricService.detail(metricCode, null);
        if (metric == null) {
            throw new CommonException("指标不存在: " + metricCode);
        }
        String status = StrUtil.blankToDefault(metric.getStatus(), "");
        String note = StrUtil.blankToDefault(param.getCaliberDiff(), param.getReason());

        if ("create".equals(metricKind)) {
            if ("draft".equals(status)) {
                GovMetricTransitionParam tp = new GovMetricTransitionParam();
                tp.setMetricCode(metricCode);
                tp.setAction("submit");
                tp.setNote(StrUtil.blankToDefault(param.getReason(), "申请发布"));
                govMetricService.transition(tp);
            } else if (!"review".equals(status)) {
                throw new CommonException("仅草稿/待发布指标可申请首次发布（当前状态=" + status + "）");
            }
        } else if ("change".equals(metricKind)) {
            if ("active".equals(status)) {
                GovMetricTransitionParam tp = new GovMetricTransitionParam();
                tp.setMetricCode(metricCode);
                tp.setAction("change");
                tp.setNote(StrUtil.blankToDefault(note, "口径变更发布申请"));
                govMetricService.transition(tp);
            } else if (!"version_review".equals(status)) {
                throw new CommonException("仅已启用/待发布·变更指标可申请口径变更发布（当前状态=" + status + "）");
            }
        } else if (!"active".equals(status) && !"version_review".equals(status)) {
            throw new CommonException("查询权限仅可申请已启用指标（当前状态=" + status + "）");
        }

        ApplyTicket t = newTicketShell(userId, TYPE_METRIC, param, "default");
        t.setTicketNo(nextPrefixedTicketNo("MET-"));
        JSONObject payload = new JSONObject();
        payload.set("metricCode", metricCode);
        payload.set("metricKind", metricKind);
        payload.set("metricName", metric.getName());
        payload.set("metricType", metric.getKind());
        payload.set("caliberDiff", param.getCaliberDiff());
        payload.set("expireLabel", param.getExpireLabel());
        t.setPayload(payload.toString());
        if (StrUtil.isBlank(t.getTitle())) {
            String kindLabel = "create".equals(metricKind) ? "指标发布"
                    : "change".equals(metricKind) ? "口径变更发布" : "指标查询权限";
            t.setTitle(kindLabel + " · " + metricCode + " · " + StrUtil.blankToDefault(metric.getName(), ""));
        }
        ticketMapper.insert(t);
        ApplyTicketItem item = newItemShell(userId, t.getId());
        item.setAssetId(null);
        item.setAction("METRIC_" + metricKind.toUpperCase(Locale.ROOT));
        item.setDetail(payload.toString());
        itemMapper.insert(item);
        return t;
    }

    private ApplyTicket createApiSubscribe(ApplyTicketCreateParam param, String userId) {
        String publicPath = StrUtil.trim(param.getPublicPath());
        String consumer = StrUtil.trim(param.getConsumerName());
        if (StrUtil.isBlank(publicPath) && StrUtil.isBlank(param.getApiBindingId())) {
            throw new CommonException("订阅申请须携带 publicPath 或 apiBindingId");
        }
        if (StrUtil.isBlank(consumer)) {
            throw new CommonException("订阅申请须填写 consumerName（调用应用名）");
        }
        ApplyTicket t = newTicketShell(userId, TYPE_API_SUBSCRIBE, param, "default");
        t.setTicketNo(nextPrefixedTicketNo("SUB-"));
        t.setExpiresAt(vip.xiaonuo.lh.modular.dataapi.service.DataapiKeyIssueService.resolveExpireAt(param.getExpireLabel()));
        JSONObject payload = new JSONObject();
        payload.set("apiBindingId", param.getApiBindingId());
        payload.set("publicPath", publicPath);
        payload.set("method", StrUtil.blankToDefault(param.getMethod(), "GET"));
        payload.set("consumerName", consumer);
        payload.set("qps", param.getQps() == null ? 100 : param.getQps());
        payload.set("expireLabel", param.getExpireLabel());
        t.setPayload(payload.toString());
        if (StrUtil.isBlank(t.getTitle())) {
            t.setTitle("API 订阅 · " + consumer + " · " + StrUtil.blankToDefault(publicPath, param.getApiBindingId()));
        }
        ticketMapper.insert(t);
        ApplyTicketItem item = newItemShell(userId, t.getId());
        item.setAssetId(null);
        item.setAction("API_SUBSCRIBE");
        item.setDetail(payload.toString());
        itemMapper.insert(item);
        return t;
    }

    private ApplyTicket createApiPublish(ApplyTicketCreateParam param, String userId) {
        String bindingId = StrUtil.trim(param.getApiBindingId());
        if (StrUtil.isBlank(bindingId)) {
            throw new CommonException("API 发布申请须携带 apiBindingId（先保存草稿绑定）");
        }
        ApplyTicket t = newTicketShell(userId, TYPE_API_PUBLISH, param, "default");
        t.setTicketNo(nextPrefixedTicketNo("API-"));
        JSONObject payload = new JSONObject();
        payload.set("apiBindingId", bindingId);
        payload.set("publicPath", param.getPublicPath());
        payload.set("method", StrUtil.blankToDefault(param.getMethod(), "GET"));
        payload.set("expireLabel", param.getExpireLabel());
        t.setPayload(payload.toString());
        if (StrUtil.isBlank(t.getTitle())) {
            t.setTitle("API 发布 · " + StrUtil.blankToDefault(param.getPublicPath(), bindingId));
        }
        ticketMapper.insert(t);
        ApplyTicketItem item = newItemShell(userId, t.getId());
        item.setAssetId(null);
        item.setAction("API_PUBLISH");
        item.setDetail(payload.toString());
        itemMapper.insert(item);
        // 回写绑定上的申请单号（草稿已存在；发布仍须审批通过）
        DataapiApiBinding binding = dataapiApiBindingMapper.selectById(bindingId);
        if (binding != null) {
            binding.setPublishTicketNo(t.getTicketNo());
            binding.setUpdateTime(new Date());
            dataapiApiBindingMapper.updateById(binding);
        }
        return t;
    }

    /**
     * 合规删除审批单（doc/合规删除.md）：只承载审批意图与痕迹，
     * 请求/计划/执行/证据仍在 {@code gov_del_*}；审批通过不写任何 grant。
     */
    private ApplyTicket createComplianceDelete(ApplyTicketCreateParam param, String userId) {
        String reqNo = StrUtil.trim(param.getReqNo());
        if (StrUtil.isBlank(reqNo)) {
            throw new CommonException("合规删除审批须携带 reqNo（gov_del_request.req_no）");
        }
        ApplyTicket t = newTicketShell(userId, TYPE_COMPLIANCE_DELETE, param, "default");
        t.setTicketNo(nextPrefixedTicketNo("CD-"));
        JSONObject payload = new JSONObject();
        payload.set("reqNo", reqNo);
        payload.set("subjectMasked", param.getSubjectMasked());
        payload.set("targetCount", param.getTargetCount());
        payload.set("legalBasis", param.getReason());
        payload.set("approvalChain", "安全岗 → 法务 → 表 Owner");
        payload.set("rollbackWindowNotice", "执行将对目标表定向 expire_snapshots(retain_last=1)，该表短期失去回滚窗口");
        t.setPayload(payload.toString());
        if (StrUtil.isBlank(t.getTitle())) {
            t.setTitle("合规删除 · " + reqNo);
        }
        ticketMapper.insert(t);

        ApplyTicketItem item = newItemShell(userId, t.getId());
        item.setAction("COMPLIANCE_DELETE");
        item.setDetail(payload.toString());
        itemMapper.insert(item);
        return t;
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
        payload.set("rowFilter", param.getRowFilter());
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
        // E7：restricted 主体/载体命中直接拒绝
        govDelProcessingGate.assertLakeExportAllowed(exportTable);
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
        LhLoginUsers.requireUserId();
        long current = param.getCurrent() == null ? 1L : param.getCurrent();
        long size = param.getSize() == null ? 20L : param.getSize();
        String typeFilter = normalizeFilterType(param.getTicketType());
        // 超管：DB 分页；Owner：先拉 pending 再按候选人过滤后内存分页（一期量级可接受）
        if (LhLoginUsers.isSuperAdmin()) {
            QueryWrapper<ApplyTicket> qw = new QueryWrapper<>();
            qw.lambda().eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                    .eq(ApplyTicket::getStatus, "pending")
                    .eq(StrUtil.isNotBlank(typeFilter), ApplyTicket::getTicketType, typeFilter)
                    .orderByAsc(ApplyTicket::getCreateTime);
            return ticketMapper.selectPage(new Page<>(current, size), qw);
        }
        List<ApplyTicket> all = ticketMapper.selectList(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getStatus, "pending")
                .eq(StrUtil.isNotBlank(typeFilter), ApplyTicket::getTicketType, typeFilter)
                .orderByAsc(ApplyTicket::getCreateTime));
        List<ApplyTicket> decidable = approvalCandidateService.filterDecidable(all);
        return slicePage(decidable, current, size);
    }

    @Override
    public Map<String, Object> kpi() {
        String userId = LhLoginUsers.requireUserId();
        Date monthStart = startOfMonth();
        List<ApplyTicket> allPending = ticketMapper.selectList(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getStatus, "pending"));
        long pending = approvalCandidateService.countDecidablePending(allPending);
        long mine = ticketMapper.selectCount(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getApplicant, userId));
        long minePending = ticketMapper.selectCount(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getApplicant, userId)
                .eq(ApplyTicket::getStatus, "pending"));
        long monthApproved = ticketMapper.selectCount(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getApplicant, userId)
                .eq(ApplyTicket::getStatus, "approved")
                .ge(ApplyTicket::getApprovedAt, monthStart));
        long monthRejected = ticketMapper.selectCount(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getApplicant, userId)
                .eq(ApplyTicket::getStatus, "rejected")
                .ge(ApplyTicket::getApprovedAt, monthStart));
        List<ApplyTicket> metricPendingAll = allPending.stream()
                .filter(t -> TYPE_METRIC.equals(t.getTicketType()))
                .toList();
        long metricPending = approvalCandidateService.countDecidablePending(metricPendingAll);
        long metricMine = ticketMapper.selectCount(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getTicketType, TYPE_METRIC)
                .eq(ApplyTicket::getApplicant, userId));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pending", pending);
        r.put("mine", mine);
        r.put("minePending", minePending);
        r.put("monthApproved", monthApproved);
        r.put("monthRejected", monthRejected);
        r.put("metricPending", metricPending);
        r.put("metricMine", metricMine);
        r.put("monthStart", monthStart);
        return r;
    }

    private static Page<ApplyTicket> slicePage(List<ApplyTicket> all, long current, long size) {
        long cur = Math.max(1L, current);
        long sz = Math.max(1L, size);
        Page<ApplyTicket> page = new Page<>(cur, sz);
        long total = all == null ? 0L : all.size();
        page.setTotal(total);
        if (total == 0L) {
            page.setRecords(List.of());
            return page;
        }
        int from = (int) Math.min((cur - 1) * sz, total);
        int to = (int) Math.min(from + sz, total);
        page.setRecords(all.subList(from, to));
        return page;
    }

    private static Date startOfMonth() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> approve(ApplyTicketDecideParam param) {
        ApplyTicket t = requireTicket(param.getId());
        approvalCandidateService.assertCanDecide(t);
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
        // 出湖 / 合规删除：仅改状态；API 发布：审批通过后自动 publish+deploy；指标发布：自动启用
        if (TYPE_LAKE_EXPORT.equals(t.getTicketType())
                || TYPE_COMPLIANCE_DELETE.equals(t.getTicketType())
                || TYPE_API_PUBLISH.equals(t.getTicketType())
                || TYPE_METRIC.equals(t.getTicketType())) {
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
            if (TYPE_API_PUBLISH.equals(t.getTicketType())) {
                Map<String, Object> pub = autoPublishApiAfterApprove(t);
                r.put("autoPublished", true);
                r.put("publish", pub);
                r.put("publishOk", pub != null && !Boolean.FALSE.equals(pub.get("ok")));
            }
            if (TYPE_METRIC.equals(t.getTicketType())) {
                Map<String, Object> pub = autoPublishMetricAfterApprove(t);
                r.put("autoPublished", true);
                r.put("metric", pub);
                r.put("publishOk", pub != null && !Boolean.FALSE.equals(pub.get("ok")));
                if (pub != null && pub.get("grantId") != null) {
                    r.put("grantId", pub.get("grantId"));
                }
            }
            return r;
        }
        // API 订阅：签发 Key（Vault + dataapi_api_key_meta）
        if (TYPE_API_SUBSCRIBE.equals(t.getTicketType())) {
            t.setStatus("approved");
            t.setApprovedBy(LhLoginUsers.requireUserId());
            t.setApprovedAt(new Date());
            t.setRemark(param.getRemark());
            t.setUpdateTime(new Date());
            t.setUpdateUser(LhLoginUsers.requireUserId());
            ticketMapper.updateById(t);
            Map<String, Object> issued = dataapiKeyIssueService.issueFromSubscribeTicket(t);
            r.put("ticket", t);
            r.put("ticketNo", t.getTicketNo());
            r.put("grantId", null);
            r.put("gravProjected", false);
            r.put("issuedKey", issued);
            return r;
        }
        // 即席扫描抬额：写 SCAN_ELEVATE grant
        if (TYPE_SCAN_ELEVATE.equals(t.getTicketType())) {
            SecAuthGrant grant = secAuthGrantService.createScanElevateFromApproval(
                    t.getId(),
                    t.getApplicant(),
                    t.getExpiresAt(),
                    StrUtil.blankToDefault(param.getRemark(), "scan elevate · " + t.getTicketNo()));
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
            r.put("gravProjected", false);
            r.put("privilege", "SCAN_ELEVATE");
            r.put("resourceType", "adhoc");
            r.put("resourceId", "platform");
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
        if (TYPE_TABLE_READ.equals(t.getTicketType())) {
            Map<String, Object> grav = gravTableAccessService.grantTableRead(
                    t, item, privilege, payload.getStr("rowFilter"));
            Object grantId = grav.get("grantId");
            item.setResultGrantId(grantId == null ? null : String.valueOf(grantId));
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
            r.putAll(grav);
            return r;
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
        ApplyTicket t = requireTicket(param.getId());
        approvalCandidateService.assertCanDecide(t);
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
        if (TYPE_METRIC.equals(t.getTicketType())) {
            revertMetricAfterReject(t, param.getRemark());
        }
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
        // E7：审批后若转 restricted，ETL sink 校验仍拒绝
        String exportTable = null;
        if (StrUtil.isNotBlank(t.getPayload())) {
            try {
                JSONObject payload = JSONUtil.parseObj(t.getPayload());
                exportTable = StrUtil.trim(payload.getStr("exportTable"));
            } catch (Exception ignored) {
                // payload 异常时跳过表级门禁，仍保留单号/状态校验
            }
        }
        if (StrUtil.isNotBlank(exportTable)) {
            govDelProcessingGate.assertLakeExportAllowed(exportTable);
        }
    }

    @Override
    public void assertApprovedComplianceTicket(String ticketNo) {
        String no = StrUtil.trim(ticketNo);
        if (StrUtil.isBlank(no)) {
            throw new CommonException("合规删除须先提交审批（缺少 ticketNo）");
        }
        ApplyTicket t = ticketMapper.selectOne(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getTicketNo, no)
                .last("LIMIT 1"));
        if (t == null) {
            throw new CommonException("合规删除审批单不存在: " + no);
        }
        if (!TYPE_COMPLIANCE_DELETE.equals(t.getTicketType())) {
            throw new CommonException("单号 " + no + " 不是合规删除审批（ticket_type=" + t.getTicketType() + "）");
        }
        if (!"approved".equals(t.getStatus())) {
            throw new CommonException("合规删除审批未通过: " + no + "（status=" + t.getStatus() + "）");
        }
    }

    @Override
    public void assertApprovedApiPublishTicket(String ticketNo, String apiBindingId) {
        String no = StrUtil.trim(ticketNo);
        if (StrUtil.isBlank(no)) {
            throw new CommonException("发布须先有已审批的 api_publish 单号：请先「保存」草稿 →「提交发布申请」→ 审批通过后再「发布」");
        }
        ApplyTicket t = ticketMapper.selectOne(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getTicketNo, no)
                .last("LIMIT 1"));
        if (t == null) {
            throw new CommonException("API 发布申请单不存在: " + no);
        }
        if (!TYPE_API_PUBLISH.equals(t.getTicketType())) {
            throw new CommonException("单号 " + no + " 不是 API 发布申请（ticket_type=" + t.getTicketType() + "）");
        }
        if (!"approved".equals(t.getStatus())) {
            throw new CommonException("API 发布申请尚未通过审批: " + no + "（status=" + t.getStatus() + "）");
        }
        if (StrUtil.isNotBlank(apiBindingId) && StrUtil.isNotBlank(t.getPayload())) {
            try {
                String bound = JSONUtil.parseObj(t.getPayload()).getStr("apiBindingId");
                if (StrUtil.isNotBlank(bound) && !bound.equals(apiBindingId)) {
                    throw new CommonException("发布单 " + no + " 与当前绑定 id 不匹配");
                }
            } catch (CommonException e) {
                throw e;
            } catch (Exception ignored) {
                /* payload 损坏时仅告警式放行类型校验 */
            }
        }
    }

    @Override
    public String findLatestApprovedApiPublishTicketNo(String apiBindingId) {
        Map<String, Object> hit = findLatestApiPublishTicket(apiBindingId);
        if (hit == null) {
            return null;
        }
        if (!"approved".equals(hit.get("status"))) {
            return null;
        }
        Object no = hit.get("ticketNo");
        return no == null ? null : String.valueOf(no);
    }

    @Override
    public Map<String, Object> findLatestApiPublishTicket(String apiBindingId) {
        if (StrUtil.isBlank(apiBindingId)) {
            return null;
        }
        List<ApplyTicket> list = ticketMapper.selectList(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getTicketType, TYPE_API_PUBLISH)
                .like(ApplyTicket::getPayload, apiBindingId)
                .orderByDesc(ApplyTicket::getCreateTime)
                .last("LIMIT 20"));
        for (ApplyTicket t : list) {
            try {
                String bound = JSONUtil.parseObj(StrUtil.blankToDefault(t.getPayload(), "{}")).getStr("apiBindingId");
                if (!apiBindingId.equals(bound)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ticketNo", t.getTicketNo());
                m.put("status", t.getStatus());
                m.put("id", t.getId());
                m.put("remark", t.getRemark());
                return m;
            } catch (Exception ignored) {
                /* skip bad payload */
            }
        }
        return null;
    }

    private void bindApprovedPublishTicket(ApplyTicket t) {
        try {
            String bindingId = JSONUtil.parseObj(StrUtil.blankToDefault(t.getPayload(), "{}")).getStr("apiBindingId");
            if (StrUtil.isBlank(bindingId)) {
                return;
            }
            DataapiApiBinding binding = dataapiApiBindingMapper.selectById(bindingId);
            if (binding == null) {
                return;
            }
            binding.setPublishTicketNo(t.getTicketNo());
            binding.setUpdateTime(new Date());
            dataapiApiBindingMapper.updateById(binding);
        } catch (Exception ignored) {
            /* 审批主路径不受绑定回写失败影响 */
        }
    }

    /**
     * 指标发布/变更审批通过后自动启用（create→approve；change→approveVersion）；
     * query → 写 metric SELECT 门户投影，闭环「申请查询→能 trial/query」。
     */
    private Map<String, Object> autoPublishMetricAfterApprove(ApplyTicket t) {
        JSONObject payload = JSONUtil.parseObj(StrUtil.blankToDefault(t.getPayload(), "{}"));
        String metricCode = payload.getStr("metricCode");
        String metricKind = StrUtil.blankToDefault(payload.getStr("metricKind"), "query").toLowerCase(Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricCode", metricCode);
        out.put("metricKind", metricKind);
        if (StrUtil.isBlank(metricCode)) {
            out.put("ok", true);
            out.put("skipped", "missing_code");
            return out;
        }
        if ("query".equals(metricKind)) {
            GovMetricVo metric = govMetricService.detail(metricCode, null);
            if (metric == null || StrUtil.isBlank(metric.getId())) {
                throw new CommonException("指标不存在，无法写入查询授权: " + metricCode);
            }
            SecAuthGrant grant = secAuthGrantService.createFromApproval(
                    t.getId(),
                    t.getApplicant(),
                    "metric",
                    metric.getId(),
                    null,
                    null,
                    "SELECT",
                    t.getExpiresAt(),
                    StrUtil.blankToDefault(t.getRemark(), "metric query grant · " + t.getTicketNo()));
            out.put("ok", true);
            out.put("grantId", grant.getId());
            out.put("privilege", "SELECT");
            out.put("resourceType", "metric");
            out.put("resourceId", metric.getId());
            return out;
        }
        GovMetricTransitionParam tp = new GovMetricTransitionParam();
        tp.setMetricCode(metricCode);
        tp.setNote(StrUtil.blankToDefault(t.getRemark(), "审批通过自动启用 · " + t.getTicketNo()));
        if ("change".equals(metricKind)) {
            tp.setAction("approveVersion");
        } else {
            tp.setAction("approve");
        }
        GovMetricVo vo = govMetricService.transition(tp);
        out.put("ok", true);
        out.put("status", vo == null ? null : vo.getStatus());
        out.put("ver", vo == null ? null : vo.getVer());
        return out;
    }

    /** 驳回：首次发布退回草稿；口径变更撤回待审并保留启用版；query 仅关单。 */
    private void revertMetricAfterReject(ApplyTicket t, String remark) {
        JSONObject payload = JSONUtil.parseObj(StrUtil.blankToDefault(t.getPayload(), "{}"));
        String metricCode = payload.getStr("metricCode");
        String metricKind = StrUtil.blankToDefault(payload.getStr("metricKind"), "query").toLowerCase(Locale.ROOT);
        if (StrUtil.isBlank(metricCode) || "query".equals(metricKind)) {
            return;
        }
        String note = StrUtil.blankToDefault(remark, "驳回退回重改 · " + t.getTicketNo());
        GovMetricTransitionParam tp = new GovMetricTransitionParam();
        tp.setMetricCode(metricCode);
        tp.setNote(note);
        if ("change".equals(metricKind)) {
            tp.setAction("cancelChange");
        } else {
            tp.setAction("reject");
        }
        try {
            govMetricService.transition(tp);
        } catch (CommonException e) {
            // 指标可能已被人工改态；驳回主路径仍成功关单
        }
    }

    /**
     * 审批通过后自动发布：写回单号 → SQLREST publish/deploy → 门户 state=published。
     * 失败抛异常，整单审批事务回滚，避免「已通过但未上线」。
     */
    private Map<String, Object> autoPublishApiAfterApprove(ApplyTicket t) {
        JSONObject payload = JSONUtil.parseObj(StrUtil.blankToDefault(t.getPayload(), "{}"));
        String bindingId = payload.getStr("apiBindingId");
        if (StrUtil.isBlank(bindingId)) {
            throw new CommonException("发布申请缺少 apiBindingId，无法自动发布");
        }
        bindApprovedPublishTicket(t);
        DataapiIdParam pubParam = new DataapiIdParam();
        pubParam.setId(bindingId);
        pubParam.setPublishTicketNo(t.getTicketNo());
        Map<String, Object> pub = dataapiService.publish(pubParam);
        if (pub == null) {
            throw new CommonException("自动发布失败：无返回");
        }
        // publish 允许 degraded；若连 pub.ok 也为 false 且未写成 published，则失败回滚
        Object binding = pub.get("binding");
        String state = null;
        if (binding instanceof Map<?, ?> m) {
            Object st = m.get("state");
            state = st == null ? null : String.valueOf(st);
        }
        if (!"published".equals(state) && Boolean.FALSE.equals(pub.get("ok"))) {
            throw new CommonException("自动发布失败：" + StrUtil.blankToDefault(
                    String.valueOf(pub.get("message")), "SQLREST publish/deploy 未成功"));
        }
        return pub;
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
        return nextPrefixedTicketNo("EXP-");
    }

    private synchronized String nextPrefixedTicketNo(String prefix) {
        // 前缀 + 雪花末 9 位；冲突极少，若撞 uq 则再生成
        for (int i = 0; i < 5; i++) {
            String id = IdUtil.getSnowflakeNextIdStr();
            String suffix = id.length() <= 9 ? id : id.substring(id.length() - 9);
            String no = prefix + suffix;
            Long cnt = ticketMapper.selectCount(new QueryWrapper<ApplyTicket>().lambda()
                    .eq(ApplyTicket::getTicketNo, no));
            if (cnt == null || cnt == 0) {
                return no;
            }
        }
        return prefix + IdUtil.getSnowflakeNextIdStr();
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
        if ("compliance".equals(t) || "compliance_delete".equals(t) || "erase".equals(t)) {
            return TYPE_COMPLIANCE_DELETE;
        }
        if ("api_publish".equals(t) || "publish".equals(t) || "publish_api".equals(t) || "dataapi_publish".equals(t)) {
            return TYPE_API_PUBLISH;
        }
        if ("api".equals(t) || "api_subscribe".equals(t) || "subscribe".equals(t) || "dataapi_subscribe".equals(t)) {
            return TYPE_API_SUBSCRIBE;
        }
        if ("metric".equals(t) || "metric_publish".equals(t) || "metrics".equals(t)) {
            return TYPE_METRIC;
        }
        if ("scan_elevate".equals(t) || "elevated".equals(t) || "elevate".equals(t)
                || "scan_quota".equals(t) || "adhoc_elevate".equals(t)) {
            return TYPE_SCAN_ELEVATE;
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
