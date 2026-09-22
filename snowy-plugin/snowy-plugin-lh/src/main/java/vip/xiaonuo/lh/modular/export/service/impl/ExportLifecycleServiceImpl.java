package vip.xiaonuo.lh.modular.export.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.lh.core.engine.DsClient;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.mapper.ApplyTicketMapper;
import vip.xiaonuo.lh.modular.apply.service.impl.ApplyTicketServiceImpl;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlDagMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlNodeMapper;
import vip.xiaonuo.lh.modular.export.service.ExportAuditService;
import vip.xiaonuo.lh.modular.export.service.ExportLifecycleService;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class ExportLifecycleServiceImpl implements ExportLifecycleService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final Set<String> OUTBOUND_SINKS = Set.of(
            "sink_ftp", "sink_rdb", "sink_bi", "sink_search");

    @Resource
    private ApplyTicketMapper ticketMapper;
    @Resource
    private IgEtlDagMapper dagMapper;
    @Resource
    private IgEtlNodeMapper nodeMapper;
    @Resource
    private DsClient dsClient;
    @Resource
    private ExportAuditService exportAuditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> expireDue() {
        Date now = new Date();
        List<ApplyTicket> due = ticketMapper.selectList(new QueryWrapper<ApplyTicket>().lambda()
                .eq(ApplyTicket::getDeleteFlag, NOT_DELETE)
                .eq(ApplyTicket::getTicketType, ApplyTicketServiceImpl.TYPE_LAKE_EXPORT)
                .eq(ApplyTicket::getStatus, "approved")
                .isNotNull(ApplyTicket::getExpiresAt)
                .le(ApplyTicket::getExpiresAt, now));

        int expiredTickets = 0;
        int pausedDags = 0;
        int auditLines = 0;
        List<Map<String, Object>> items = new ArrayList<>();

        for (ApplyTicket ticket : due) {
            List<Map<String, Object>> sinks = findOutboundSinks(ticket.getTicketNo());
            List<Map<String, Object>> paused = new ArrayList<>();
            Set<String> seenDag = new HashSet<>();
            for (Map<String, Object> sink : sinks) {
                String dagId = str(sink.get("dagId"));
                if (StrUtil.isBlank(dagId) || !seenDag.add(dagId)) {
                    continue;
                }
                IgEtlDag dag = dagMapper.selectById(dagId);
                if (dag == null) {
                    continue;
                }
                Map<String, Object> pauseResult = pauseDag(dag);
                if (Boolean.TRUE.equals(pauseResult.get("paused"))) {
                    pausedDags++;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("dagId", dag.getId());
                one.put("dagCode", dag.getDagCode());
                one.put("nodeKey", sink.get("nodeKey"));
                one.put("ds", pauseResult.get("ds"));
                paused.add(one);
            }

            JSONObject payload = parsePayload(ticket);
            payload.set("exportExpiredAt", now);
            payload.set("exportExpirePausedDags", paused);
            payload.set("purgeNotice", "出湖授权已到期：已停作业；请下游删除副本（外部删不掉须书面残留）");
            ticket.setPayload(payload.toString());
            ticket.setStatus("expired");
            ticket.setUpdateTime(now);
            ticket.setRemark(StrUtil.blankToDefault(ticket.getRemark(), "")
                    + (StrUtil.isBlank(ticket.getRemark()) ? "" : " · ")
                    + "到期自动停作业");
            ticketMapper.updateById(ticket);
            expiredTickets++;

            Map<String, Object> extras = new LinkedHashMap<>();
            if (!paused.isEmpty()) {
                Map<String, Object> first = paused.get(0);
                extras.put("dagId", first.get("dagId"));
                extras.put("dagCode", first.get("dagCode"));
                extras.put("nodeKey", first.get("nodeKey"));
            }
            extras.put("pausedDags", paused);
            extras.put("purgeNotice", payload.getStr("purgeNotice"));
            extras.put("approver", "system");
            Map<String, Object> audit = exportAuditService.record(
                    ExportAuditService.EVENT_EXPIRE_STOP, ticket, extras);
            auditLines++;
            exportAuditService.record(ExportAuditService.EVENT_NOTICE_PURGE, ticket, Map.of(
                    "approver", "system",
                    "purgeNotice", payload.getStr("purgeNotice"),
                    "detail", "notify downstream purge"));
            auditLines++;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ticketNo", ticket.getTicketNo());
            item.put("paused", paused.size());
            item.put("auditId", audit.get("id"));
            items.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("scanned", due.size());
        out.put("expiredTickets", expiredTickets);
        out.put("pausedDags", pausedDags);
        out.put("auditLines", auditLines);
        out.put("items", items);
        return out;
    }

    private Map<String, Object> pauseDag(IgEtlDag dag) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paused", false);
        if (dag == null) {
            return out;
        }
        String prev = StrUtil.blankToDefault(dag.getStatus(), "");
        if ("paused".equals(prev)) {
            out.put("paused", true);
            out.put("already", true);
            return out;
        }
        // draft 无调度；prod → paused + DS OFFLINE
        if ("prod".equals(prev) && StrUtil.isNotBlank(dag.getDsWorkflowCode())) {
            Map<String, Object> ds = dsClient.setScheduleState(dag.getDsWorkflowCode(), "OFFLINE");
            out.put("ds", ds);
        }
        dag.setStatus("paused");
        dag.setRevision(dag.getRevision() == null ? 1 : dag.getRevision() + 1);
        dagMapper.updateById(dag);
        out.put("paused", true);
        out.put("from", prev);
        return out;
    }

    private List<Map<String, Object>> findOutboundSinks(String ticketNo) {
        String tn = StrUtil.trim(ticketNo);
        if (StrUtil.isBlank(tn)) {
            return List.of();
        }
        List<IgEtlNode> nodes = nodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                .in(IgEtlNode::getNodeType, OUTBOUND_SINKS));
        List<Map<String, Object>> out = new ArrayList<>();
        for (IgEtlNode n : nodes) {
            JSONObject conf = parseConf(n.getConfJson());
            if (!tn.equals(StrUtil.trim(conf.getStr("ticketNo")))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dagId", n.getDagId());
            m.put("nodeKey", n.getNodeKey());
            m.put("nodeType", n.getNodeType());
            IgEtlDag dag = dagMapper.selectById(n.getDagId());
            m.put("dagCode", dag == null ? null : dag.getDagCode());
            out.add(m);
        }
        return out;
    }

    private static JSONObject parsePayload(ApplyTicket t) {
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
