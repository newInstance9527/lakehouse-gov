package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlEdge;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;
import vip.xiaonuo.lh.modular.lineage.param.GovLineageEdgeUpsertParam;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;
import vip.xiaonuo.lh.modular.quality.param.GovDqEvaluateParam;
import vip.xiaonuo.lh.modular.quality.service.GovDqService;
import vip.xiaonuo.lh.modular.standard.param.GovStdMappingUpsertParam;
import vip.xiaonuo.lh.modular.standard.service.GovStdService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布/试跑副作用：标准映射、字段血缘、质量规则 runs（均 soft-fail，除非 quality blockOnFail）
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Component
public class IgEtlPublishSideEffects {

    private static final Logger log = LoggerFactory.getLogger(IgEtlPublishSideEffects.class);

    @Resource
    private GovStdService govStdService;
    @Resource
    private GovLineageService govLineageService;
    @Resource
    private GovDqService govDqService;
    @Resource
    private GovDqRuleMapper dqRuleMapper;
    @Resource
    private vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper datasourceMapper;

    public Map<String, Object> apply(IgEtlDag dag, List<IgEtlNode> nodes, List<IgEtlEdge> edges) {
        Map<String, IgEtlNode> byKey = new LinkedHashMap<>();
        for (IgEtlNode n : nodes) {
            byKey.put(n.getNodeKey(), n);
        }
        Map<String, List<String>> parents = new HashMap<>();
        for (IgEtlNode n : nodes) {
            parents.put(n.getNodeKey(), new ArrayList<>());
        }
        if (edges != null) {
            for (IgEtlEdge e : edges) {
                List<String> ps = parents.get(e.getToNodeKey());
                if (ps != null) {
                    ps.add(e.getFromNodeKey());
                }
            }
        }

        Map<String, String> tableOf = new HashMap<>();
        for (int pass = 0; pass < Math.max(nodes.size(), 1) + 2; pass++) {
            boolean changed = false;
            for (IgEtlNode n : nodes) {
                String next = resolveNodeTable(n, parents, byKey, tableOf, dag);
                String prev = tableOf.get(n.getNodeKey());
                if (!StrUtil.equals(prev, next)) {
                    tableOf.put(n.getNodeKey(), next);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }

        String ws = StrUtil.blankToDefault(dag.getWs(), "default");
        String etlJobId = StrUtil.blankToDefault(dag.getDagCode(), dag.getId());
        int mappingOk = 0;
        int mappingFail = 0;
        int lineageOk = 0;
        int lineageFail = 0;
        List<String> notes = new ArrayList<>();

        int retired = 0;
        try {
            retired = govLineageService.retireEdgesByEtlJob(ws, etlJobId);
        } catch (Exception ex) {
            notes.add("retireEdges: " + ex.getMessage());
            log.warn("retire lineage edges soft-fail dag={}: {}", dag.getId(), ex.getMessage());
        }

        for (IgEtlNode n : nodes) {
            JSONObject conf = parseConf(n.getConfJson());
            warnMissingMetricCode(n, conf, notes);
            JSONArray maps = fieldMapsOf(conf);
            if (maps == null || maps.isEmpty()) {
                continue;
            }
            String toTable = tableOf.getOrDefault(n.getNodeKey(), "node:" + n.getNodeKey());
            String fromTable = firstParentTable(n.getNodeKey(), parents, tableOf);
            String dsId = findUpstreamDsId(n.getNodeKey(), parents, byKey);
            boolean isMapping = "mapping".equals(n.getNodeType());
            String nodeCodeSet = conf.getStr("codeSetId");
            String nodeStdRef = conf.getStr("stdRef");

            for (int i = 0; i < maps.size(); i++) {
                JSONObject row = maps.getJSONObject(i);
                if (row == null) {
                    continue;
                }
                String src = firstNonBlank(row.getStr("src"), row.getStr("source"));
                String dst = firstNonBlank(row.getStr("dst"), row.getStr("target"), row.getStr("std"));
                if (StrUtil.isBlank(src) || StrUtil.isBlank(dst)) {
                    continue;
                }
                if (Boolean.TRUE.equals(row.getBool("skip"))) {
                    continue;
                }
                String transform = firstNonBlank(row.getStr("transform"), row.getStr("expr"), row.getStr("rule"));

                // C3 字段血缘
                try {
                    GovLineageEdgeUpsertParam edge = new GovLineageEdgeUpsertParam();
                    edge.setWs(ws);
                    edge.setFromTable(StrUtil.blankToDefault(fromTable, "upstream:" + n.getNodeKey()));
                    edge.setFromField(src.trim());
                    edge.setToTable(toTable);
                    edge.setToField(dst.trim());
                    edge.setTransformText(transform);
                    edge.setConfidence("explicit");
                    edge.setEtlJobId(etlJobId);
                    edge.setRemark("etl deploy node=" + n.getNodeKey());
                    govLineageService.upsertField(edge);
                    lineageOk++;
                } catch (Exception ex) {
                    lineageFail++;
                    notes.add("lineage " + n.getNodeKey() + "." + src + "→" + dst + ": " + ex.getMessage());
                    log.warn("publish lineage soft-fail dag={} node={}: {}", dag.getId(), n.getNodeKey(), ex.getMessage());
                }

                // C1 标准映射（mapping 节点，或带 std/码值的映射行）
                if (isMapping || StrUtil.isNotBlank(nodeStdRef) || StrUtil.isNotBlank(nodeCodeSet)
                        || StrUtil.isNotBlank(row.getStr("std"))) {
                    try {
                        GovStdMappingUpsertParam mp = new GovStdMappingUpsertParam();
                        mp.setWs(ws);
                        mp.setSrcObject(StrUtil.blankToDefault(fromTable, "src"));
                        mp.setSrcField(src.trim());
                        mp.setStdFieldName(firstNonBlank(row.getStr("std"), dst, nodeStdRef).trim());
                        mp.setCodeSetId(firstNonBlank(row.getStr("codeSetId"), nodeCodeSet));
                        mp.setTargetTable(toTable);
                        mp.setRuleText(StrUtil.blankToDefault(transform, conf.getStr("strategy")));
                        mp.setDsId(dsId);
                        mp.setEtlJobId(etlJobId);
                        mp.setStatus("ok");
                        mp.setRemark("etl deploy " + dag.getDagCode() + " / " + n.getNodeKey());
                        govStdService.upsertMapping(mp);
                        mappingOk++;
                    } catch (Exception ex) {
                        mappingFail++;
                        notes.add("stdMapping " + n.getNodeKey() + "." + src + ": " + ex.getMessage());
                        log.warn("publish std mapping soft-fail dag={} node={}: {}", dag.getId(), n.getNodeKey(), ex.getMessage());
                    }
                }
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("stdMappingOk", mappingOk);
        r.put("stdMappingFail", mappingFail);
        r.put("lineageOk", lineageOk);
        r.put("lineageFail", lineageFail);
        r.put("retired", retired);

        // 拓扑推断边：无 fieldMaps 时仍能构图（from.table * → to.table *）
        int topoOk = 0;
        int topoFail = 0;
        for (IgEtlNode n : nodes) {
            String toTable = tableOf.get(n.getNodeKey());
            String fromTable = firstParentTable(n.getNodeKey(), parents, tableOf);
            if (StrUtil.isBlank(fromTable) || StrUtil.isBlank(toTable)) {
                continue;
            }
            if (fromTable.equals(toTable)) {
                continue;
            }
            if (fromTable.startsWith("node:") || toTable.startsWith("node:")
                    || toTable.startsWith("dag.") || fromTable.startsWith("dag.")) {
                continue;
            }
            // 仅 sink / mapping 写拓扑边，避免中间透传噪音
            String nt = StrUtil.blankToDefault(n.getNodeType(), "");
            if (!"mapping".equals(nt) && !nt.startsWith("sink_")) {
                continue;
            }
            try {
                GovLineageEdgeUpsertParam edge = new GovLineageEdgeUpsertParam();
                edge.setWs(ws);
                edge.setFromTable(fromTable);
                edge.setFromField("*");
                edge.setToTable(toTable);
                edge.setToField("*");
                edge.setTransformText("etl topology " + n.getNodeKey());
                edge.setConfidence("inferred");
                edge.setEtlJobId(etlJobId);
                edge.setRemark("etl deploy topology node=" + n.getNodeKey());
                govLineageService.upsertField(edge);
                topoOk++;
            } catch (Exception ex) {
                topoFail++;
                notes.add("topo " + n.getNodeKey() + ": " + ex.getMessage());
                log.warn("publish topology lineage soft-fail dag={} node={}: {}",
                        dag.getId(), n.getNodeKey(), ex.getMessage());
            }
        }
        r.put("topologyOk", topoOk);
        r.put("topologyFail", topoFail);
        if (!notes.isEmpty()) {
            r.put("notes", notes.size() > 8 ? notes.subList(0, 8) : notes);
        }
        return r;
    }

    /**
     * quality 节点：调 {@link GovDqService#evaluate}（默认真探数）写 {@code gov_dq_rule_run}。
     * <p>{@code blockOnFail=true} 且存在失败时 {@code blocked=true}，由调用方决定是否阻断试跑/发布。
     * DS SHELL 同期 curl 同一 evaluate，exit 1 阻断下游。</p>
     *
     * @param jobRunId 关联 ig_etl_run.run_id 或 deploy 标记
     */
    public Map<String, Object> applyQuality(IgEtlDag dag, List<IgEtlNode> nodes, String jobRunId) {
        String ws = StrUtil.blankToDefault(dag.getWs(), "default");
        int runOk = 0;
        int runFail = 0;
        int blocked = 0;
        int missing = 0;
        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> nodeResults = new ArrayList<>();

        for (IgEtlNode n : nodes) {
            if (!"quality".equals(n.getNodeType())) {
                continue;
            }
            JSONObject conf = parseConf(n.getConfJson());
            List<String> ruleIds = ruleIdsOf(conf);
            boolean blockOnFail = conf.getBool("blockOnFail", true);
            Map<String, Object> nr = new LinkedHashMap<>();
            nr.put("nodeKey", n.getNodeKey());
            nr.put("blockOnFail", blockOnFail);
            nr.put("ruleCount", ruleIds.size());
            if (ruleIds.isEmpty()) {
                nr.put("skipped", true);
                nr.put("reason", "无 ruleIds（草稿 rules 不写 gov_dq_rule_run）");
                nodeResults.add(nr);
                continue;
            }
            try {
                GovDqEvaluateParam ep = new GovDqEvaluateParam();
                ep.setWs(ws);
                ep.setJobRunId(jobRunId);
                ep.setNodeKey(n.getNodeKey());
                ep.setBlockOnFail(blockOnFail);
                ep.setRuleIds(ruleIds);
                Map<String, Object> ev = govDqService.evaluate(ep);
                int nodePass = ((Number) ev.getOrDefault("pass", 0)).intValue();
                int nodeFail = ((Number) ev.getOrDefault("fail", 0)).intValue();
                boolean nodeBlocked = Boolean.TRUE.equals(ev.get("blocked"));
                runOk += nodePass;
                runFail += nodeFail;
                if (nodeBlocked) {
                    blocked++;
                }
                Object missNotes = ev.get("notes");
                if (missNotes instanceof List<?> nl) {
                    for (Object o : nl) {
                        missing++;
                        notes.add(String.valueOf(o));
                    }
                }
                nr.put("pass", nodePass);
                nr.put("fail", nodeFail);
                nr.put("blocked", nodeBlocked);
                nr.put("evaluate", ev);
                nodeResults.add(nr);
            } catch (Exception ex) {
                runFail++;
                notes.add("quality " + n.getNodeKey() + ": " + ex.getMessage());
                nr.put("pass", 0);
                nr.put("fail", ruleIds.size());
                nr.put("blocked", blockOnFail);
                nodeResults.add(nr);
                log.warn("quality evaluate soft-fail dag={} node={}: {}", dag.getId(), n.getNodeKey(), ex.getMessage());
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("qualityRunOk", runOk);
        r.put("qualityRunFail", runFail);
        r.put("qualityBlocked", blocked);
        r.put("qualityMissing", missing);
        r.put("qualityNodes", nodeResults);
        r.put("blocked", blocked > 0 || nodeResults.stream().anyMatch(m -> Boolean.TRUE.equals(m.get("blocked"))));
        if (!notes.isEmpty()) {
            r.put("notes", notes.size() > 8 ? notes.subList(0, 8) : notes);
        }
        return r;
    }

    /** 校验用：ruleIds 是否均存在于 gov_dq_rule */
    public List<String> missingRuleIds(JSONObject conf) {
        List<String> missing = new ArrayList<>();
        for (String id : ruleIdsOf(conf)) {
            GovDqRule rule = dqRuleMapper.selectById(id);
            if (rule == null || "DELETED".equals(rule.getDeleteFlag())) {
                missing.add(id);
            }
        }
        return missing;
    }

    private static List<String> ruleIdsOf(JSONObject conf) {
        List<String> ids = new ArrayList<>();
        JSONArray arr = conf.getJSONArray("ruleIds");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                String id = arr.getStr(i);
                if (StrUtil.isBlank(id) && arr.get(i) instanceof JSONObject o) {
                    id = firstNonBlank(o.getStr("id"), o.getStr("ruleId"));
                }
                if (StrUtil.isNotBlank(id)) {
                    ids.add(id.trim());
                }
            }
        }
        return ids;
    }

    private String resolveNodeTable(
            IgEtlNode n,
            Map<String, List<String>> parents,
            Map<String, IgEtlNode> byKey,
            Map<String, String> memo,
            IgEtlDag dag) {
        if (memo.containsKey(n.getNodeKey()) && StrUtil.isNotBlank(memo.get(n.getNodeKey()))) {
            return memo.get(n.getNodeKey());
        }
        JSONObject conf = parseConf(n.getConfJson());
        String explicit = firstNonBlank(
                conf.getStr("destTable"),
                conf.getStr("targetTable"),
                conf.getStr("dst"),
                conf.getStr("table"),
                conf.getStr("index"),
                conf.getStr("topic"));
        if (StrUtil.isBlank(explicit) && conf.get("tables") instanceof JSONArray arr && !arr.isEmpty()) {
            explicit = arr.getStr(0);
        }
        if (StrUtil.isNotBlank(explicit)) {
            return qualifyTable(explicit.trim(), conf);
        }
        if ("mapping".equals(n.getNodeType()) && StrUtil.isNotBlank(conf.getStr("stdRef"))) {
            return "std:" + conf.getStr("stdRef").trim();
        }
        String inherited = firstParentTable(n.getNodeKey(), parents, memo);
        if (StrUtil.isNotBlank(inherited) && !inherited.startsWith("node:")) {
            // 透传类节点沿用上游表；mapping/sink 无显式表时用逻辑名
            if (!"mapping".equals(n.getNodeType()) && !String.valueOf(n.getNodeType()).startsWith("sink_")) {
                return inherited;
            }
        }
        return "dag." + StrUtil.blankToDefault(dag.getDagCode(), dag.getId()) + "." + n.getNodeKey();
    }

    /**
     * 表节点按「数据源管理」可读名限定：{@code {数据源名称}.{库?}.{表}}。
     * 禁止再用 {@code ds.ds_雪花id} 这种编码展示。
     */
    private String qualifyTable(String table, JSONObject conf) {
        if (StrUtil.isBlank(table)) {
            return table;
        }
        String t = table.trim();
        // 已是「数据源名.xxx」且不是旧 ds.ds_ 前缀，保留
        if (t.contains(".") && !t.startsWith("ds.ds_") && !t.startsWith("ds.id")) {
            // 若未带数据源名但仍是 schema.table，继续用下方逻辑包一层数据源名
            if (!needsDsPrefix(t, conf)) {
                return t;
            }
        }
        String dsId = StrUtil.trim(conf.getStr("dsId"));
        String database = StrUtil.trim(conf.getStr("database"));
        // 去掉旧前缀再拼
        if (t.startsWith("ds.")) {
            int last = t.lastIndexOf('.');
            if (last > 0 && last < t.length() - 1) {
                t = t.substring(last + 1);
            }
        }
        String dsName = resolveDsDisplayName(dsId, conf);
        if (StrUtil.isBlank(dsName)) {
            return t;
        }
        dsName = sanitizeDsName(dsName);
        if (StrUtil.isNotBlank(database) && !t.contains(".")) {
            return dsName + "." + database + "." + t;
        }
        // t 已是 schema.table 时：数据源名.schema.table
        return dsName + "." + t;
    }

    private boolean needsDsPrefix(String table, JSONObject conf) {
        String dsName = resolveDsDisplayName(StrUtil.trim(conf.getStr("dsId")), conf);
        if (StrUtil.isBlank(dsName)) {
            return false;
        }
        return !table.startsWith(sanitizeDsName(dsName) + ".");
    }

    /** 优先数据源「名称」，与数据源管理列表一致 */
    private String resolveDsDisplayName(String dsId, JSONObject conf) {
        if (StrUtil.isNotBlank(dsId)) {
            try {
                vip.xiaonuo.lh.modular.datasource.entity.LhDatasource ds = datasourceMapper.selectById(dsId);
                if (ds != null) {
                    String name = firstNonBlank(ds.getName(), ds.getDsCode(), ds.getType());
                    if (StrUtil.isNotBlank(name) && !name.startsWith("ds_")) {
                        return name.trim();
                    }
                    // dsCode 是 ds_雪花 时仍用 name（哪怕空再用 type）
                    if (StrUtil.isNotBlank(ds.getName())) {
                        return ds.getName().trim();
                    }
                    if (StrUtil.isNotBlank(ds.getType())) {
                        return ds.getType().trim();
                    }
                }
            } catch (Exception ignored) {
                // soft
            }
        }
        return firstNonBlank(conf.getStr("dsName"), conf.getStr("dbType"), conf.getStr("engine"));
    }

    private static String sanitizeDsName(String name) {
        return name.trim().replace(' ', '_').replace('/', '_');
    }

    private String firstParentTable(String nodeKey, Map<String, List<String>> parents, Map<String, String> tableOf) {
        List<String> ps = parents.getOrDefault(nodeKey, List.of());
        for (String p : ps) {
            String t = tableOf.get(p);
            if (StrUtil.isNotBlank(t)) {
                return t;
            }
        }
        return ps.isEmpty() ? null : "node:" + ps.get(0);
    }

    private String findUpstreamDsId(String nodeKey, Map<String, List<String>> parents, Map<String, IgEtlNode> byKey) {
        List<String> frontier = new ArrayList<>(parents.getOrDefault(nodeKey, List.of()));
        int guard = 0;
        while (!frontier.isEmpty() && guard++ < 64) {
            String pk = frontier.remove(0);
            IgEtlNode pn = byKey.get(pk);
            if (pn == null) {
                continue;
            }
            JSONObject conf = parseConf(pn.getConfJson());
            if (StrUtil.isNotBlank(conf.getStr("dsId"))) {
                return conf.getStr("dsId");
            }
            frontier.addAll(parents.getOrDefault(pk, List.of()));
        }
        return null;
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

    /**
     * 指标作业 SHOULD 绑定 metric_code（命名约束 §2.6）：指标向节点缺省时记 warn，不阻断发布。
     */
    private static void warnMissingMetricCode(IgEtlNode n, JSONObject conf, List<String> notes) {
        if (n == null || conf == null || notes == null) {
            return;
        }
        String purpose = StrUtil.blankToDefault(conf.getStr("purpose"), "").toLowerCase();
        String nodeType = StrUtil.blankToDefault(n.getNodeType(), "").toLowerCase();
        boolean metricish = purpose.contains("metric")
                || "metric".equalsIgnoreCase(conf.getStr("sourceKind"))
                || nodeType.contains("metric")
                || StrUtil.isNotBlank(conf.getStr("metricCode"))
                || StrUtil.isNotBlank(conf.getStr("metric_code"));
        if (!metricish) {
            return;
        }
        String code = firstNonBlank(conf.getStr("metric_code"), conf.getStr("metricCode"));
        if (StrUtil.isBlank(code)) {
            notes.add("metric_code missing on node " + n.getNodeKey() + " (SHOULD bind gov_metric.metric_code)");
        }
    }

    private static JSONArray fieldMapsOf(JSONObject conf) {
        JSONArray maps = conf.getJSONArray("fieldMaps");
        if (maps == null || maps.isEmpty()) {
            maps = conf.getJSONArray("mapList");
        }
        return maps;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
