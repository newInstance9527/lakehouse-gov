package vip.xiaonuo.lh.modular.observability.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.ws.LhWsFilters;
import vip.xiaonuo.lh.modular.etl.param.IgEtlBackfillParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlDagEditParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlPageParam;
import vip.xiaonuo.lh.modular.etl.service.IgEtlService;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.observability.service.LhObsTasksService;
import vip.xiaonuo.lh.modular.recon.support.ReconPartitionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class LhObsTasksServiceImpl implements LhObsTasksService {

    @Resource
    private IgEtlService igEtlService;
    @Resource
    private ReconPartitionService reconPartitionService;
    @Resource
    private GovLineageService govLineageService;

    @Override
    public Map<String, Object> listTasks(String group, String ws) {
        String workspace = LhWsFilters.listWs(ws);
        String g = StrUtil.blankToDefault(group, "all").trim().toLowerCase(Locale.ROOT);

        IgEtlPageParam pageParam = new IgEtlPageParam();
        pageParam.setWs(workspace);
        Page<Map<String, Object>> dagPage = igEtlService.pageDags(pageParam);
        List<Map<String, Object>> dags = dagPage.getRecords() == null ? List.of() : dagPage.getRecords();

        Page<Map<String, Object>> runPage = igEtlService.pageRuns(null, workspace);
        List<Map<String, Object>> runs = runPage.getRecords() == null ? List.of() : runPage.getRecords();
        Map<String, List<Map<String, Object>>> runsByDag = new LinkedHashMap<>();
        for (Map<String, Object> run : runs) {
            String dagId = str(run.get("dagId"));
            if (StrUtil.isBlank(dagId)) {
                continue;
            }
            runsByDag.computeIfAbsent(dagId, k -> new ArrayList<>()).add(run);
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Map<String, Object> dag : dags) {
            String eng = StrUtil.blankToDefault(str(dag.get("defaultEngine")), str(dag.get("engine")))
                    .toLowerCase(Locale.ROOT);
            boolean flink = eng.contains("flink") || eng.contains("stream");
            String taskGroup = flink ? "flink" : "ds";
            if ("etl".equals(g) || "all".equals(g)
                    || (flink && "flink".equals(g))
                    || (!flink && "ds".equals(g))) {
                String id = str(dag.get("id"));
                List<Map<String, Object>> recent = runsByDag.getOrDefault(id, List.of());
                if (recent.size() > 10) {
                    recent = recent.subList(0, 10);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("group", taskGroup);
                row.put("kind", "etl_dag");
                row.put("name", StrUtil.blankToDefault(str(dag.get("name")), str(dag.get("dagCode"))));
                row.put("dagCode", dag.get("dagCode"));
                row.put("status", dag.get("status"));
                row.put("lastStatus", recent.isEmpty() ? dag.get("status") : recent.get(0).get("status"));
                row.put("cron", dag.get("cron"));
                row.put("owner", dag.get("owner"));
                row.put("sla", dag.get("sla"));
                row.put("env", dag.get("env"));
                row.put("engine", eng);
                row.put("ws", StrUtil.blankToDefault(str(dag.get("ws")), workspace));
                row.put("lag", null);
                row.put("recentRuns", recent);
                row.put("logDeepLink", id == null ? null : "/ops?dagId=" + id);
                tasks.add(row);
            }
        }

        if ("all".equals(g) || "recon".equals(g)) {
            Map<String, Object> recon = reconPartitionService.listRecent(null, null, 20);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> records = (List<Map<String, Object>>) recon.getOrDefault("records", List.of());
            for (Map<String, Object> r : records) {
                Map<String, Object> row = new LinkedHashMap<>();
                String metric = str(r.get("metricCode"));
                String table = StrUtil.blankToDefault(str(r.get("ckTable")), str(r.get("lakeTable")));
                row.put("id", "recon:" + StrUtil.blankToDefault(str(r.get("id")), metric + ":" + r.get("partitionKey")));
                row.put("group", "recon");
                row.put("kind", "recon_partition");
                row.put("name", StrUtil.blankToDefault(table, metric));
                row.put("status", r.get("status"));
                row.put("lastStatus", r.get("status"));
                row.put("metricCode", metric);
                row.put("partitionKey", r.get("partitionKey"));
                row.put("ws", workspace);
                row.put("recentRuns", List.of(r));
                row.put("logDeepLink", "/reliability");
                tasks.add(row);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("group", g);
        out.put("records", tasks);
        out.put("total", tasks.size());
        out.put("source", tasks.isEmpty() ? "empty" : "etl+recon");
        out.put("hint", "门面聚合 IgEtl + recon_partition；quality 分组见 /lh/dq");
        return out;
    }

    @Override
    public Map<String, Object> action(String id, Map<String, Object> body) {
        if (StrUtil.isBlank(id)) {
            throw new CommonException("id 必填");
        }
        if (id.startsWith("recon:")) {
            throw new CommonException("对账行不支持 pause/resume；请走 /lh/recon 或补数门面");
        }
        String action = body == null ? null : str(body.get("action"));
        if (StrUtil.isBlank(action)) {
            throw new CommonException("action 必填：pause|resume|rerun|backfill");
        }
        String act = action.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("action", act);

        switch (act) {
            case "pause" -> {
                IgEtlDagEditParam p = new IgEtlDagEditParam();
                p.setId(id);
                p.setStatus("paused");
                out.put("result", igEtlService.editDag(p));
            }
            case "resume" -> {
                IgEtlDagEditParam p = new IgEtlDagEditParam();
                p.setId(id);
                p.setStatus("prod");
                out.put("result", igEtlService.editDag(p));
            }
            case "rerun", "backfill" -> {
                IgEtlBackfillParam p = new IgEtlBackfillParam();
                p.setId(id);
                p.setMarkKey(StrUtil.blankToDefault(str(body.get("markKey")), "dt"));
                String mv = str(body.get("markValue"));
                if (StrUtil.isBlank(mv)) {
                    throw new CommonException("backfill/rerun 须 markValue");
                }
                p.setMarkValue(mv);
                p.setEnv(str(body.get("env")));
                p.setConfirmReqNo(str(body.get("confirmReqNo")));
                out.put("result", igEtlService.backfill(p));
            }
            default -> throw new CommonException("不支持的 action: " + act);
        }
        out.put("ok", true);
        return out;
    }

    @Override
    public Map<String, Object> rerunDownstream(String id, Map<String, Object> body) {
        if (StrUtil.isBlank(id)) {
            throw new CommonException("id 必填");
        }
        String table = body == null ? null : str(body.get("table"));
        String field = body == null ? null : str(body.get("field"));
        String markKey = body == null ? null : StrUtil.blankToDefault(str(body.get("markKey")), "dt");
        String markValue = body == null ? null : str(body.get("markValue"));
        String ws = body == null ? null : str(body.get("ws"));

        Map<String, Object> impact;
        if (StrUtil.isNotBlank(table)) {
            impact = govLineageService.impact(table, field, null, 1, 8, ws);
        } else {
            Map<String, Object> detail = igEtlService.detail(id);
            impact = new LinkedHashMap<>();
            impact.put("dagId", id);
            impact.put("dagCode", detail.get("dagCode"));
            impact.put("downstream", List.of());
            impact.put("hint", "未传 table 时仅返回本 DAG；传 table 走 §6.7 impact 下游");
        }

        List<Map<String, Object>> downstream = extractDownstream(impact);

        List<Map<String, Object>> tickets = new ArrayList<>();
        if (StrUtil.isNotBlank(markValue)) {
            IgEtlBackfillParam p = new IgEtlBackfillParam();
            p.setId(id);
            p.setMarkKey(markKey);
            p.setMarkValue(markValue);
            p.setEnv(body == null ? null : str(body.get("env")));
            p.setConfirmReqNo(body == null ? null : str(body.get("confirmReqNo")));
            Map<String, Object> primary = igEtlService.backfill(p);
            Map<String, Object> ticket = new LinkedHashMap<>();
            ticket.put("dagId", id);
            ticket.put("parallel", false);
            ticket.put("result", primary);
            tickets.add(ticket);
        }

        for (Map<String, Object> d : downstream) {
            Map<String, Object> ticket = new LinkedHashMap<>();
            ticket.put("target", d);
            ticket.put("status", "pending");
            ticket.put("hint", "下游补数工单：绑定下游 DAG 后串/并行触发 backfill");
            tickets.add(ticket);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("impact", impact);
        out.put("tickets", tickets);
        out.put("ok", true);
        return out;
    }

    @Override
    public Map<String, Object> slaSummary(String ws) {
        String workspace = LhWsFilters.listWs(ws);
        IgEtlPageParam pageParam = new IgEtlPageParam();
        pageParam.setWs(workspace);
        Page<Map<String, Object>> dagPage = igEtlService.pageDags(pageParam);
        List<Map<String, Object>> dags = dagPage.getRecords() == null ? List.of() : dagPage.getRecords();
        Page<Map<String, Object>> runPage = igEtlService.pageRuns(null, workspace);
        List<Map<String, Object>> runs = runPage.getRecords() == null ? List.of() : runPage.getRecords();

        long withSla = dags.stream().filter(d -> StrUtil.isNotBlank(str(d.get("sla")))).count();
        long runOk = runs.stream().filter(r -> "success".equalsIgnoreCase(str(r.get("status")))).count();
        long runFail = runs.stream().filter(r -> "failed".equalsIgnoreCase(str(r.get("status")))).count();

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> dag : dags) {
            if (StrUtil.isBlank(str(dag.get("sla")))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", dag.get("id"));
            item.put("name", dag.get("name"));
            item.put("dagCode", dag.get("dagCode"));
            item.put("sla", dag.get("sla"));
            item.put("status", dag.get("status"));
            item.put("logDeepLink", "/ops?dagId=" + dag.get("id"));
            items.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", workspace);
        out.put("dagCount", dags.size());
        out.put("slaBoundCount", withSla);
        out.put("recentRunOk", runOk);
        out.put("recentRunFail", runFail);
        out.put("items", items);
        out.put("source", dags.isEmpty() ? "empty" : "etl");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractDownstream(Map<String, Object> impact) {
        if (impact == null) {
            return List.of();
        }
        Object down = impact.get("downstream");
        if (down instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        Object downAlt = impact.get("down");
        if (downAlt instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return List.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
