package vip.xiaonuo.lh.modular.export.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.mapper.ApplyTicketMapper;
import vip.xiaonuo.lh.modular.apply.service.impl.ApplyTicketServiceImpl;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlDagMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlNodeMapper;
import vip.xiaonuo.lh.modular.export.service.ExportAuditService;
import vip.xiaonuo.lh.modular.export.service.ExportBoardService;
import vip.xiaonuo.lh.modular.export.service.ExportLifecycleService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ExportBoardServiceImpl implements ExportBoardService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String WS_DEFAULT = "default";
    private static final Set<String> OUTBOUND_SINKS = Set.of(
            "sink_ftp", "sink_rdb", "sink_bi", "sink_search");
    private static final Pattern DAYS = Pattern.compile("(\\d+)\\s*天");

    @Resource
    private ApplyTicketMapper ticketMapper;
    @Resource
    private IgEtlDagMapper dagMapper;
    @Resource
    private IgEtlNodeMapper nodeMapper;
    @Resource
    private ExportAuditService exportAuditService;
    @Resource
    private ExportLifecycleService exportLifecycleService;

    @Override
    public Map<String, Object> summary(String ws) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        List<ApplyTicket> tickets = listExportTickets(workspace, null);
        List<Map<String, Object>> sinks = listOutboundSinks(workspace);

        long approved = tickets.stream().filter(t -> "approved".equals(t.getStatus())).count();
        long pending = tickets.stream().filter(t -> "pending".equals(t.getStatus())).count();
        long expiring = tickets.stream().filter(this::isExpiringSoon).count();
        Set<String> targets = new HashSet<>();
        for (ApplyTicket t : tickets) {
            JSONObject p = payload(t);
            String tg = p.getStr("exportTarget");
            if (StrUtil.isNotBlank(tg)) {
                targets.add(tg.trim());
            }
        }
        for (Map<String, Object> s : sinks) {
            Object tg = s.get("target");
            if (tg != null && StrUtil.isNotBlank(String.valueOf(tg))) {
                targets.add(String.valueOf(tg).trim());
            }
        }
        long activeJobs = sinks.stream()
                .filter(s -> !"paused".equals(String.valueOf(s.get("dagStatus"))))
                .count();
        if (activeJobs == 0) {
            activeJobs = approved;
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", workspace);
        r.put("activeJobs", activeJobs);
        r.put("approved", approved);
        r.put("pending", pending);
        r.put("targets", targets.size());
        r.put("expiringSoon", expiring);
        r.put("ticketCount", tickets.size());
        r.put("sinkCount", sinks.size());
        return r;
    }

    @Override
    public List<Map<String, Object>> jobs(String ws, String status, String q) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        String statusFilter = StrUtil.trim(status);
        String keyword = StrUtil.trim(q);

        Map<String, Map<String, Object>> sinksByTicket = new HashMap<>();
        for (Map<String, Object> sink : listOutboundSinks(workspace)) {
            String tn = StrUtil.trim(String.valueOf(sink.getOrDefault("ticketNo", "")));
            if (StrUtil.isBlank(tn)) {
                continue;
            }
            sinksByTicket.putIfAbsent(tn, sink);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApplyTicket t : listExportTickets(workspace, null)) {
            JSONObject p = payload(t);
            String ticketNo = t.getTicketNo();
            Map<String, Object> sink = sinksByTicket.get(ticketNo);
            Map<String, Object> row = toJobRow(t, p, sink);
            if (StrUtil.isNotBlank(statusFilter) && !statusFilter.equals(row.get("status"))) {
                continue;
            }
            if (StrUtil.isNotBlank(keyword) && !matchQ(row, keyword)) {
                continue;
            }
            rows.add(row);
            sinksByTicket.remove(ticketNo);
        }
        // ETL sink 挂了 ticketNo 但申请单不在本 ws / 已删：仍展示
        for (Map.Entry<String, Map<String, Object>> e : sinksByTicket.entrySet()) {
            Map<String, Object> sink = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("job", e.getKey());
            row.put("ticketNo", e.getKey());
            row.put("ticketId", null);
            row.put("src", sink.get("src"));
            row.put("target", sink.get("target"));
            row.put("purpose", sink.get("purpose"));
            row.put("freq", sink.get("freq"));
            row.put("mask", sink.getOrDefault("mask", "待配置"));
            row.put("expire", sink.getOrDefault("expire", "—"));
            row.put("status", "ok");
            row.put("ticketStatus", "linked");
            row.put("dagId", sink.get("dagId"));
            row.put("dagCode", sink.get("dagCode"));
            row.put("nodeKey", sink.get("nodeKey"));
            row.put("nodeType", sink.get("nodeType"));
            if (StrUtil.isNotBlank(statusFilter) && !statusFilter.equals(row.get("status"))) {
                continue;
            }
            if (StrUtil.isNotBlank(keyword) && !matchQ(row, keyword)) {
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    @Override
    public Map<String, Object> audit(String ws, String ticketNo) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        List<Map<String, Object>> formal = exportAuditService.list(workspace, ticketNo);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("ws", workspace);
        if (!formal.isEmpty()) {
            r.put("source", "gov_export_audit");
            r.put("count", formal.size());
            r.put("lines", formal);
            r.put("hint", "出库审计已正式落库 gov_export_audit；Grav 表属性为 soft-fail 标记");
            return r;
        }
        // 回落：历史无审计行时仍给 soft 摘要，便于过渡
        List<Map<String, Object>> jobRows = jobs(workspace, null, ticketNo);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> j : jobRows) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("time", j.get("approvedAt") != null ? j.get("approvedAt") : j.get("createTime"));
            line.put("ticketNo", j.get("ticketNo"));
            line.put("eventType", "soft");
            line.put("src", j.get("src"));
            line.put("target", j.get("target"));
            line.put("purpose", j.get("purpose"));
            line.put("approver", j.get("approvedBy"));
            line.put("status", j.get("ticketStatus"));
            line.put("dagCode", j.get("dagCode"));
            lines.add(line);
        }
        r.put("source", "soft");
        r.put("count", lines.size());
        r.put("lines", lines);
        r.put("hint", "暂无正式审计行；已回落申请单+ETL 摘要。新审批/到期停作业会写入 gov_export_audit");
        return r;
    }

    @Override
    public Map<String, Object> expireDue() {
        return exportLifecycleService.expireDue();
    }

    private Map<String, Object> toJobRow(ApplyTicket t, JSONObject p, Map<String, Object> sink) {
        String src = firstNonBlank(p.getStr("exportTable"), p.getStr("assetCode"), t.getTitle());
        String target = firstNonBlank(p.getStr("exportTarget"), sink == null ? null : str(sink.get("target")));
        String expireLabel = firstNonBlank(p.getStr("expireLabel"), formatExpire(t.getExpiresAt()));
        String ticketStatus = StrUtil.blankToDefault(t.getStatus(), "pending");
        String uiStatus = resolveUiStatus(ticketStatus, t.getExpiresAt());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("job", t.getTicketNo());
        row.put("ticketNo", t.getTicketNo());
        row.put("ticketId", t.getId());
        row.put("src", shortTable(src));
        row.put("target", StrUtil.blankToDefault(target, "—"));
        row.put("purpose", StrUtil.blankToDefault(t.getReason(), p.getStr("purpose")));
        row.put("freq", sink != null ? sink.get("freq") : ("pending".equals(ticketStatus) ? "待审批" : "待挂 ETL"));
        row.put("mask", sink != null ? sink.getOrDefault("mask", "待配置") : "待配置");
        row.put("expire", StrUtil.blankToDefault(expireLabel, "长期"));
        row.put("status", uiStatus);
        row.put("ticketStatus", ticketStatus);
        row.put("createTime", t.getCreateTime());
        row.put("approvedAt", t.getApprovedAt());
        row.put("approvedBy", t.getApprovedBy());
        row.put("expiresAt", t.getExpiresAt());
        if (sink != null) {
            row.put("dagId", sink.get("dagId"));
            row.put("dagCode", sink.get("dagCode"));
            row.put("nodeKey", sink.get("nodeKey"));
            row.put("nodeType", sink.get("nodeType"));
        }
        return row;
    }

    private List<ApplyTicket> listExportTickets(String ws, String status) {
        QueryWrapper<ApplyTicket> qw = new QueryWrapper<>();
        qw.lambda().eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getTicketType, ApplyTicketServiceImpl.TYPE_LAKE_EXPORT)
                .eq(StrUtil.isNotBlank(ws), ApplyTicket::getWs, ws)
                .eq(StrUtil.isNotBlank(status), ApplyTicket::getStatus, status)
                .orderByDesc(ApplyTicket::getCreateTime);
        return ticketMapper.selectList(qw);
    }

    private List<Map<String, Object>> listOutboundSinks(String ws) {
        List<IgEtlDag> dags = dagMapper.selectList(new QueryWrapper<IgEtlDag>().lambda()
                .eq(StrUtil.isNotBlank(ws), IgEtlDag::getWs, ws));
        Map<String, IgEtlDag> byId = dags.stream()
                .collect(Collectors.toMap(IgEtlDag::getId, d -> d, (a, b) -> a));
        if (byId.isEmpty()) {
            return List.of();
        }
        List<IgEtlNode> nodes = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                .in(IgEtlNode::getDagId, byId.keySet()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (IgEtlNode n : nodes) {
            String type = StrUtil.blankToDefault(n.getNodeType(), "");
            if (!OUTBOUND_SINKS.contains(type)) {
                continue;
            }
            JSONObject conf = parseConf(n.getConfJson());
            String ticketNo = StrUtil.trim(conf.getStr("ticketNo"));
            if (StrUtil.isBlank(ticketNo)) {
                continue;
            }
            IgEtlDag dag = byId.get(n.getDagId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticketNo", ticketNo);
            m.put("dagId", n.getDagId());
            m.put("dagCode", dag == null ? null : dag.getDagCode());
            m.put("dagStatus", dag == null ? null : dag.getStatus());
            m.put("nodeKey", n.getNodeKey());
            m.put("nodeType", type);
            m.put("src", firstNonBlank(conf.getStr("srcTable"), conf.getStr("table"), conf.getStr("src")));
            m.put("target", firstNonBlank(
                    conf.getStr("target"), conf.getStr("targetSystem"), conf.getStr("host"),
                    conf.getStr("index"), conf.getStr("dataset"), conf.getStr("dsId")));
            m.put("purpose", firstNonBlank(conf.getStr("purpose"), conf.getStr("remark")));
            m.put("freq", dag != null && StrUtil.isNotBlank(dag.getCron()) ? dag.getCron() : "已挂 DAG");
            m.put("mask", firstNonBlank(conf.getStr("mask"), conf.getStr("maskRule"), "待配置"));
            m.put("expire", conf.getStr("expireLabel"));
            out.add(m);
        }
        return out;
    }

    private boolean isExpiringSoon(ApplyTicket t) {
        if (t.getExpiresAt() == null || !"approved".equals(t.getStatus())) {
            return false;
        }
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 7);
        Date now = new Date();
        return !t.getExpiresAt().before(now) && !t.getExpiresAt().after(cal.getTime());
    }

    private String resolveUiStatus(String ticketStatus, Date expiresAt) {
        if ("pending".equals(ticketStatus)) {
            return "warn";
        }
        if ("expired".equals(ticketStatus)
                || "rejected".equals(ticketStatus)
                || "cancelled".equals(ticketStatus)) {
            return "urgent";
        }
        if (expiresAt != null) {
            Date now = new Date();
            if (expiresAt.before(now)) {
                return "urgent";
            }
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, 7);
            if (!expiresAt.after(cal.getTime())) {
                return "warn";
            }
        }
        return "ok";
    }

    private static boolean matchQ(Map<String, Object> row, String q) {
        String hay = (str(row.get("job")) + " " + str(row.get("src")) + " " + str(row.get("target"))
                + " " + str(row.get("purpose"))).toLowerCase(Locale.ROOT);
        return hay.contains(q.toLowerCase(Locale.ROOT));
    }

    private static JSONObject payload(ApplyTicket t) {
        try {
            return JSONUtil.parseObj(StrUtil.blankToDefault(t.getPayload(), "{}"));
        } catch (Exception e) {
            return JSONUtil.createObj();
        }
    }

    private static JSONObject parseConf(String confJson) {
        if (StrUtil.isBlank(confJson)) {
            return JSONUtil.createObj();
        }
        try {
            return JSONUtil.parseObj(confJson);
        } catch (Exception e) {
            return JSONUtil.createObj();
        }
    }

    private static String formatExpire(Date d) {
        if (d == null) {
            return "长期";
        }
        return String.valueOf(d);
    }

    private static String shortTable(String src) {
        if (StrUtil.isBlank(src)) {
            return "—";
        }
        String s = src.trim();
        return s.replaceFirst("^(ads|dwd|dws)\\.", "").replaceFirst("^[\\w]+\\.", "");
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 供申请创建解析「N天」；保留给同模块复用说明 */
    public static Integer parseDaysLabel(String label) {
        if (StrUtil.isBlank(label) || "长期".equals(label)) {
            return null;
        }
        Matcher m = DAYS.matcher(label);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }
}
