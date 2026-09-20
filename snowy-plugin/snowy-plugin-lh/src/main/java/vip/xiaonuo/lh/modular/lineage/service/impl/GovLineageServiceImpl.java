package vip.xiaonuo.lh.modular.lineage.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.enums.CommonSortOrderEnum;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.core.engine.MarquezClient;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlEdge;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlDagMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlEdgeMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlNodeMapper;
import vip.xiaonuo.lh.modular.etl.support.IgEtlPublishSideEffects;
import vip.xiaonuo.lh.modular.lineage.entity.CbLineageSyncWatermark;
import vip.xiaonuo.lh.modular.lineage.entity.GovLineageFieldEdge;
import vip.xiaonuo.lh.modular.lineage.mapper.CbLineageSyncWatermarkMapper;
import vip.xiaonuo.lh.modular.lineage.mapper.GovLineageFieldEdgeMapper;
import vip.xiaonuo.lh.modular.lineage.param.GovLineageEdgeUpsertParam;
import vip.xiaonuo.lh.modular.lineage.param.GovLineageIdParam;
import vip.xiaonuo.lh.modular.lineage.param.GovLineagePageParam;
import vip.xiaonuo.lh.modular.lineage.result.GovLineageEdgeVo;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字段血缘：OM 表级图 soft-fail + 门户字段边缓存
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Service
public class GovLineageServiceImpl implements GovLineageService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovLineageFieldEdgeMapper edgeMapper;
    @Resource
    private CbLineageSyncWatermarkMapper watermarkMapper;
    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private MarquezClient marquezClient;
    @Resource
    private vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper govAssetMapper;
    @Resource
    private IgEtlDagMapper igEtlDagMapper;
    @Resource
    private IgEtlNodeMapper igEtlNodeMapper;
    @Resource
    private IgEtlEdgeMapper igEtlEdgeMapper;
    @Resource
    @Lazy
    private IgEtlPublishSideEffects publishSideEffects;

    @Override
    public Map<String, Object> graph(String node, String focus, String omFqn, Integer upDepth, Integer downDepth, String ws) {
        int up = clamp(upDepth, 5);
        int down = clamp(downDepth, 5);
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        String focusKey = firstNonBlank(omFqn, node, focus);
        if (StrUtil.isBlank(focusKey)) {
            focusKey = pickDefaultFocusTable(workspace);
        }

        Map<String, Object> fromEdges = buildGraphFromEdges(focusKey, up, down, workspace);
        boolean portalHas = fromEdges.get("nodes") instanceof List<?> nl && !nl.isEmpty();

        Map<String, Object> om = openMetadataClient.getTableLineage(focusKey, up, down);
        boolean omOk = Boolean.TRUE.equals(om.get("ok"));
        Map<String, Object> omGraph = omOk ? flattenOmLineageGraph(om.get("data"), focusKey) : Map.of();
        boolean omHasGraph = omGraph != null && omGraph.get("nodes") instanceof List<?> onl && !onl.isEmpty();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("focus", focusKey);
        r.put("upDepth", up);
        r.put("downDepth", down);
        r.put("om", om);
        // 门户字段边（ETL 发布/同步）为图谱主源；OM 有图且门户为空时回退 OM
        if (portalHas) {
            r.put("nodes", fromEdges.get("nodes"));
            r.put("edges", fromEdges.get("edges"));
            r.put("source", omHasGraph ? "portal_edges+openmetadata" : "portal_edges");
            if (omHasGraph) {
                r.put("omGraph", omGraph);
            }
        } else if (omHasGraph) {
            r.put("nodes", omGraph.get("nodes"));
            r.put("edges", omGraph.get("edges"));
            r.put("source", "openmetadata");
            r.put("portalEdges", fromEdges);
        } else {
            r.put("nodes", fromEdges.get("nodes"));
            r.put("edges", fromEdges.get("edges"));
            r.put("source", omOk ? "openmetadata+portal_edges" : "portal_edges");
        }
        if (!omOk) {
            r.put("omDegraded", true);
            r.put("omMessage", om.get("message"));
        }
        return r;
    }

    @Override
    public Map<String, Object> impact(String node, String focus, String omFqn, Integer upDepth, Integer downDepth, String ws) {
        int up = clamp(upDepth, 5);
        int down = clamp(downDepth, 5);
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        String focusKey = firstNonBlank(omFqn, node, focus);
        if (StrUtil.isBlank(focusKey)) {
            focusKey = pickDefaultFocusTable(workspace);
        }
        List<GovLineageFieldEdge> all = listActiveEdges(workspace);

        Set<String> upTables = expandTables(focusKey, all, true, up);
        Set<String> downTables = expandTables(focusKey, all, false, down);
        upTables.remove(normalizeTable(focusKey));
        downTables.remove(normalizeTable(focusKey));

        Map<String, String> tableJob = new HashMap<>();
        for (GovLineageFieldEdge e : all) {
            if (StrUtil.isNotBlank(e.getEtlJobId()) && StrUtil.isNotBlank(e.getToTable())) {
                tableJob.putIfAbsent(normalizeTable(e.getToTable()), e.getEtlJobId());
            }
            if (StrUtil.isNotBlank(e.getEtlJobId()) && StrUtil.isNotBlank(e.getFromTable())) {
                tableJob.putIfAbsent(normalizeTable(e.getFromTable()), e.getEtlJobId());
            }
        }

        // OM 下游实体补充（有则并入 downTables）
        String impactSource = "portal_edges";
        Map<String, Object> om = openMetadataClient.getTableLineage(focusKey, up, down);
        if (Boolean.TRUE.equals(om.get("ok"))) {
            Set<String> omDown = extractOmEntityNames(om.get("data"), false);
            if (!omDown.isEmpty()) {
                downTables.addAll(omDown);
                impactSource = "openmetadata+portal_edges";
            }
            Set<String> omUp = extractOmEntityNames(om.get("data"), true);
            if (!omUp.isEmpty()) {
                upTables.addAll(omUp);
                impactSource = "openmetadata+portal_edges";
            }
            if (!omDown.isEmpty() || !omUp.isEmpty()) {
                // 若 OM 有实体且门户边为空，标为以 OM 为主
                if (all.isEmpty()) {
                    impactSource = "openmetadata";
                }
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("focus", focusKey);
        r.put("source", impactSource);
        r.put("up", upTables.stream().map(t -> impactItem(t, "上游", tableJob.get(normalizeTable(t)), workspace))
                .collect(Collectors.toList()));
        r.put("down", downTables.stream().map(t -> impactItem(t, "下游", tableJob.get(normalizeTable(t)), workspace))
                .collect(Collectors.toList()));
        r.put("upCount", upTables.size());
        r.put("downCount", downTables.size());
        return r;
    }

    @Override
    public Page<GovLineageEdgeVo> pageFields(GovLineagePageParam param) {
        QueryWrapper<GovLineageFieldEdge> qw = new QueryWrapper<>();
        qw.lambda().eq(GovLineageFieldEdge::getDeleteFlag, NOT_DELETE);
        String workspace = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        qw.lambda().eq(GovLineageFieldEdge::getWs, workspace);
        String q = StrUtil.blankToDefault(param.getQ(), param.getKeyword());
        if (StrUtil.isNotBlank(q)) {
            qw.and(w -> w.like("from_table", q).or().like("from_field", q)
                    .or().like("to_table", q).or().like("to_field", q)
                    .or().like("transform_text", q));
        }
        if (StrUtil.isNotBlank(param.getFocusTable()) && StrUtil.isNotBlank(param.getFocusField())) {
            String ft = param.getFocusTable();
            String ff = param.getFocusField();
            qw.and(w -> w
                    .and(a -> a.eq("from_table", ft).eq("from_field", ff))
                    .or(b -> b.eq("to_table", ft).eq("to_field", ff)));
        } else if (StrUtil.isNotBlank(param.getFocusTable())) {
            String ft = param.getFocusTable();
            qw.and(w -> w.eq("from_table", ft).or().eq("to_table", ft));
        }
        if (StrUtil.isNotBlank(param.getSortField())) {
            CommonSortOrderEnum.validate(param.getSortOrder());
            qw.orderBy(true, CommonSortOrderEnum.ASC.getValue().equalsIgnoreCase(param.getSortOrder()),
                    StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByDesc(GovLineageFieldEdge::getUpdateTime);
        }
        Page<GovLineageFieldEdge> page = edgeMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<GovLineageEdgeVo> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(this::toVo).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovLineageEdgeVo upsertField(GovLineageEdgeUpsertParam param) {
        String workspace = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        String job = StrUtil.blankToDefault(param.getEtlJobId(), "");
        GovLineageFieldEdge existing = edgeMapper.selectOne(new QueryWrapper<GovLineageFieldEdge>().lambda()
                .eq(GovLineageFieldEdge::getWs, workspace)
                .eq(GovLineageFieldEdge::getFromTable, param.getFromTable())
                .eq(GovLineageFieldEdge::getFromField, param.getFromField())
                .eq(GovLineageFieldEdge::getToTable, param.getToTable())
                .eq(GovLineageFieldEdge::getToField, param.getToField())
                .eq(GovLineageFieldEdge::getEtlJobId, job)
                .eq(GovLineageFieldEdge::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (existing == null && StrUtil.isNotBlank(param.getId())) {
            existing = edgeMapper.selectById(param.getId());
        }
        Date now = new Date();
        if (existing == null) {
            existing = new GovLineageFieldEdge();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setRevision(1);
            existing.setStatus("active");
            existing.setWs(workspace);
            existing.setDeleteFlag(NOT_DELETE);
            existing.setCreateTime(now);
        } else {
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            existing.setDeleteFlag(NOT_DELETE);
        }
        existing.setFromTable(param.getFromTable().trim());
        existing.setFromField(param.getFromField().trim());
        existing.setToTable(param.getToTable().trim());
        existing.setToField(param.getToField().trim());
        existing.setTransformText(param.getTransformText());
        existing.setConfidence(StrUtil.blankToDefault(param.getConfidence(), "explicit"));
        existing.setEtlJobId(job);
        existing.setOmFromFqn(param.getOmFromFqn());
        existing.setOmToFqn(param.getOmToFqn());
        existing.setRemark(param.getRemark());
        existing.setUpdateTime(now);
        if (edgeMapper.selectById(existing.getId()) == null) {
            edgeMapper.insert(existing);
        } else {
            edgeMapper.updateById(existing);
        }
        return toVo(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteField(GovLineageIdParam param) {
        GovLineageFieldEdge row = edgeMapper.selectById(param.getId());
        if (row == null) {
            throw new CommonException("字段边不存在");
        }
        row.setDeleteFlag("DELETED");
        row.setUpdateTime(new Date());
        edgeMapper.updateById(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int retireEdgesByEtlJob(String ws, String etlJobId) {
        if (StrUtil.isBlank(etlJobId)) {
            return 0;
        }
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<GovLineageFieldEdge> rows = edgeMapper.selectList(new QueryWrapper<GovLineageFieldEdge>().lambda()
                .eq(GovLineageFieldEdge::getWs, workspace)
                .eq(GovLineageFieldEdge::getEtlJobId, etlJobId.trim())
                .eq(GovLineageFieldEdge::getDeleteFlag, NOT_DELETE));
        Date now = new Date();
        int n = 0;
        for (GovLineageFieldEdge row : rows) {
            row.setDeleteFlag("DELETED");
            row.setStatus("inactive");
            row.setUpdateTime(now);
            edgeMapper.updateById(row);
            n++;
        }
        return n;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncFields(String ws, String etlJobId) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        String markKey = "ws:" + workspace + (StrUtil.isBlank(etlJobId) ? "" : (":job:" + etlJobId));
        String markValue = String.valueOf(System.currentTimeMillis());

        // 重扫 ETL DAG：fieldMaps → 显式字段边；拓扑 → 推断 *→* 边
        int dagCount = 0;
        int lineageOk = 0;
        int lineageFail = 0;
        int topologyOk = 0;
        List<String> notes = new ArrayList<>();
        QueryWrapper<IgEtlDag> dqw = new QueryWrapper<>();
        dqw.lambda().eq(IgEtlDag::getWs, workspace);
        if (StrUtil.isNotBlank(etlJobId)) {
            dqw.and(w -> w.eq("dag_code", etlJobId).or().eq("id", etlJobId));
        }
        List<IgEtlDag> dags = igEtlDagMapper.selectList(dqw);
        for (IgEtlDag dag : dags) {
            List<IgEtlNode> nodes = igEtlNodeMapper.selectList(new QueryWrapper<IgEtlNode>().lambda()
                    .eq(IgEtlNode::getDagId, dag.getId()));
            List<IgEtlEdge> edges = igEtlEdgeMapper.selectList(new QueryWrapper<IgEtlEdge>().lambda()
                    .eq(IgEtlEdge::getDagId, dag.getId()));
            try {
                Map<String, Object> applied = publishSideEffects.apply(dag, nodes, edges);
                dagCount++;
                lineageOk += toInt(applied.get("lineageOk"));
                lineageFail += toInt(applied.get("lineageFail"));
                topologyOk += toInt(applied.get("topologyOk"));
                Object n = applied.get("notes");
                if (n instanceof List<?> list && !list.isEmpty()) {
                    for (Object o : list) {
                        if (notes.size() < 8) {
                            notes.add(String.valueOf(o));
                        }
                    }
                }
            } catch (Exception e) {
                lineageFail++;
                notes.add("dag " + dag.getDagCode() + ": " + e.getMessage());
            }
        }

        upsertWatermark("etl_parse", markKey, markValue);
        upsertWatermark("lineage_parse", markKey, markValue);

        Map<String, Object> mz = marquezClient.listNamespaces();
        if (Boolean.TRUE.equals(mz.get("ok"))) {
            upsertWatermark("marquez", markKey, markValue);
        }

        long edges = edgeMapper.selectCount(new QueryWrapper<GovLineageFieldEdge>().lambda()
                .eq(GovLineageFieldEdge::getWs, workspace)
                .eq(GovLineageFieldEdge::getDeleteFlag, NOT_DELETE));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("ws", workspace);
        r.put("dagCount", dagCount);
        r.put("lineageOk", lineageOk);
        r.put("lineageFail", lineageFail);
        r.put("topologyOk", topologyOk);
        r.put("edgeCount", edges);
        r.put("markKey", markKey);
        r.put("markValue", markValue);
        r.put("marquez", mz);
        r.put("hint", "已重扫 ETL fieldMaps/拓扑写入 gov_lineage_field_edge；OM 表级图 soft-fail；Marquez 作业血缘待全量投影");
        if (!notes.isEmpty()) {
            r.put("notes", notes);
        }
        return r;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 门户边中任选一张表作默认焦点；无边则空串 */
    private String pickDefaultFocusTable(String ws) {
        List<GovLineageFieldEdge> all = listActiveEdges(ws);
        for (GovLineageFieldEdge e : all) {
            if (StrUtil.isNotBlank(e.getToTable()) && !"*".equals(e.getToField())) {
                return e.getToTable();
            }
        }
        for (GovLineageFieldEdge e : all) {
            if (StrUtil.isNotBlank(e.getToTable())) {
                return e.getToTable();
            }
            if (StrUtil.isNotBlank(e.getFromTable())) {
                return e.getFromTable();
            }
        }
        return "";
    }

    @Override
    public Map<String, Object> syncStatus(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<CbLineageSyncWatermark> list = watermarkMapper.selectList(new QueryWrapper<CbLineageSyncWatermark>()
                .likeRight("mark_key", "ws:" + workspace)
                .orderByDesc("update_time"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ws", workspace);
        r.put("watermarks", list.stream().map(w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceSystem", w.getSourceSystem());
            m.put("markKey", w.getMarkKey());
            m.put("markValue", w.getMarkValue());
            m.put("updateTime", w.getUpdateTime());
            return m;
        }).collect(Collectors.toList()));
        return r;
    }

    @Override
    public Map<String, Object> changeEval(String table, String field, String toType, String ws) {
        if (StrUtil.isBlank(table) || StrUtil.isBlank(field)) {
            throw new CommonException("table/field 不能为空");
        }
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<GovLineageFieldEdge> all = listActiveEdges(workspace);
        List<Map<String, Object>> downstream = propagateFields(table, field, all, 8);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", IdUtil.getSnowflakeNextIdStr());
        r.put("table", table);
        r.put("field", field);
        r.put("toType", toType);
        r.put("impactCount", downstream.size());
        r.put("downstream", downstream);
        r.put("passed", downstream.isEmpty() || StrUtil.isBlank(toType));
        r.put("status", "draft");
        r.put("message", downstream.isEmpty()
                ? "暂无下游字段边"
                : ("影响 " + downstream.size() + " 个下游字段对象"));
        return r;
    }

    @Override
    public Map<String, Object> blockDdl(String table, String field, String reason, String ws) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("blocked", true);
        r.put("table", table);
        r.put("field", field);
        r.put("reason", StrUtil.blankToDefault(reason, "变更评估未通过"));
        r.put("ws", StrUtil.blankToDefault(ws, WS_DEFAULT));
        r.put("ticketId", IdUtil.getSnowflakeNextIdStr());
        r.put("hint", "已登记阻断意图；正式对接 Schema 契约 / 发布门禁");
        return r;
    }

    @Override
    public Map<String, Object> marquezNamespaces() {
        return marquezClient.listNamespaces();
    }

    private Map<String, Object> buildGraphFromEdges(String focus, int up, int down, String ws) {
        List<GovLineageFieldEdge> all = listActiveEdges(ws);
        String focusTable = normalizeTable(focus);
        Set<String> tables = new LinkedHashSet<>();
        tables.add(focusTable);
        tables.addAll(expandTables(focus, all, true, up));
        tables.addAll(expandTables(focus, all, false, down));

        List<Map<String, Object>> nodes = new ArrayList<>();
        int i = 0;
        for (String t : tables) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", t);
            n.put("name", t);
            n.put("type", "table");
            n.put("layer", guessLayer(t));
            n.put("hop", t.equalsIgnoreCase(focusTable) ? 0 : null);
            n.put("x", 80 + (i % 5) * 220);
            n.put("y", 80 + (i / 5) * 120);
            nodes.add(n);
            i++;
        }
        Set<String> edgeKeys = new HashSet<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (GovLineageFieldEdge e : all) {
            String from = normalizeTable(e.getFromTable());
            String to = normalizeTable(e.getToTable());
            if (!tables.contains(from) || !tables.contains(to)) {
                continue;
            }
            String key = from + "->" + to;
            if (!edgeKeys.add(key)) {
                continue;
            }
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", from);
            edge.put("to", to);
            edge.put("transform", e.getTransformText());
            edges.add(edge);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nodes", nodes);
        r.put("edges", edges);
        return r;
    }

    private Set<String> expandTables(String focus, List<GovLineageFieldEdge> all, boolean upstream, int depth) {
        String start = normalizeTable(focus);
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> q = new ArrayDeque<>();
        Map<String, Integer> hops = new HashMap<>();
        q.add(start);
        hops.put(start, 0);
        while (!q.isEmpty()) {
            String cur = q.poll();
            int h = hops.getOrDefault(cur, 0);
            if (h >= depth) {
                continue;
            }
            for (GovLineageFieldEdge e : all) {
                String from = normalizeTable(e.getFromTable());
                String to = normalizeTable(e.getToTable());
                String next = upstream ? (to.equalsIgnoreCase(cur) ? from : null)
                        : (from.equalsIgnoreCase(cur) ? to : null);
                if (next == null || visited.contains(next.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                visited.add(next.toLowerCase(Locale.ROOT));
                hops.put(next, h + 1);
                q.add(next);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (String k : hops.keySet()) {
            if (!k.equalsIgnoreCase(start)) {
                out.add(k);
            }
        }
        // also include start for graph builder
        out.add(start);
        return out;
    }

    private List<Map<String, Object>> propagateFields(String table, String field,
                                                      List<GovLineageFieldEdge> all, int maxHop) {
        String startKey = fieldKey(table, field);
        List<Map<String, Object>> out = new ArrayList<>();
        Queue<String[]> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(new String[]{table, field, "0", ""});
        seen.add(startKey);
        while (!q.isEmpty()) {
            String[] cur = q.poll();
            int hop = Integer.parseInt(cur[2]);
            if (hop >= maxHop) {
                continue;
            }
            for (GovLineageFieldEdge e : all) {
                if (!normalizeTable(e.getFromTable()).equalsIgnoreCase(normalizeTable(cur[0]))
                        || !e.getFromField().equalsIgnoreCase(cur[1])) {
                    continue;
                }
                String nk = fieldKey(e.getToTable(), e.getToField());
                if (!seen.add(nk)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", nk);
                item.put("tableKey", e.getToTable());
                item.put("fieldName", e.getToField());
                item.put("name", e.getToTable() + "." + e.getToField());
                item.put("transform", e.getTransformText());
                item.put("hop", hop + 1);
                item.put("confidence", e.getConfidence());
                out.add(item);
                q.add(new String[]{e.getToTable(), e.getToField(), String.valueOf(hop + 1), e.getTransformText()});
            }
        }
        return out;
    }

    private void upsertWatermark(String source, String markKey, String markValue) {
        CbLineageSyncWatermark row = watermarkMapper.selectOne(new QueryWrapper<CbLineageSyncWatermark>()
                .eq("source_system", source).eq("mark_key", markKey).last("LIMIT 1"));
        Date now = new Date();
        if (row == null) {
            row = new CbLineageSyncWatermark();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setSourceSystem(source);
            row.setMarkKey(markKey);
            row.setMarkValue(markValue);
            row.setUpdateTime(now);
            watermarkMapper.insert(row);
        } else {
            row.setMarkValue(markValue);
            row.setUpdateTime(now);
            watermarkMapper.updateById(row);
        }
    }

    private List<GovLineageFieldEdge> listActiveEdges(String ws) {
        return edgeMapper.selectList(new QueryWrapper<GovLineageFieldEdge>().lambda()
                .eq(GovLineageFieldEdge::getWs, ws)
                .eq(GovLineageFieldEdge::getDeleteFlag, NOT_DELETE));
    }

    private GovLineageEdgeVo toVo(GovLineageFieldEdge e) {
        GovLineageEdgeVo v = new GovLineageEdgeVo();
        v.setId(e.getId());
        v.setFromTable(e.getFromTable());
        v.setFromField(e.getFromField());
        v.setToTable(e.getToTable());
        v.setToField(e.getToField());
        v.setTransform(e.getTransformText());
        v.setConfidence(e.getConfidence());
        v.setEtlJobId(e.getEtlJobId());
        v.setOmFromFqn(e.getOmFromFqn());
        v.setOmToFqn(e.getOmToFqn());
        v.setStatus(e.getStatus());
        return v;
    }

    private Map<String, Object> impactItem(String table, String dir, String etlJobId, String ws) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", table);
        String kind = classifyImpactKind(table, etlJobId);
        m.put("type", kind);
        m.put("layer", guessLayer(table));
        m.put("note", dir + "依赖");

        vip.xiaonuo.lh.modular.catalog.entity.GovAsset asset = findAssetByTable(table, ws);
        if (asset != null) {
            m.put("assetId", asset.getId());
            m.put("assetCode", asset.getAssetCode());
            String owner = firstNonBlank(asset.getTechOwner(), asset.getBizOwner());
            m.put("owner", owner);
            m.put("techOwner", asset.getTechOwner());
            m.put("bizOwner", asset.getBizOwner());
        }
        if (StrUtil.isNotBlank(etlJobId)) {
            m.put("etlJobId", etlJobId);
            try {
                vip.xiaonuo.lh.modular.etl.entity.IgEtlDag dag = igEtlDagMapper.selectById(etlJobId);
                if (dag != null) {
                    m.put("jobName", dag.getName());
                    if (StrUtil.isBlank(String.valueOf(m.getOrDefault("owner", "")))) {
                        m.put("owner", dag.getOwner());
                    }
                    m.put("jobOwner", dag.getOwner());
                    if ("表".equals(kind) || guessLayer(table).equals(kind)) {
                        m.put("type", "作业");
                    }
                }
            } catch (Exception ignored) {
                // soft-fail
            }
        }
        if (m.get("owner") == null) {
            m.put("owner", null);
        }
        return m;
    }

    private vip.xiaonuo.lh.modular.catalog.entity.GovAsset findAssetByTable(String table, String ws) {
        if (StrUtil.isBlank(table)) {
            return null;
        }
        String shortName = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        try {
            List<vip.xiaonuo.lh.modular.catalog.entity.GovAsset> hits =
                    govAssetMapper.selectList(new QueryWrapper<vip.xiaonuo.lh.modular.catalog.entity.GovAsset>().lambda()
                            .eq(vip.xiaonuo.lh.modular.catalog.entity.GovAsset::getWs, workspace)
                            .eq(vip.xiaonuo.lh.modular.catalog.entity.GovAsset::getDeleteFlag, NOT_DELETE)
                            .and(w -> w.eq(vip.xiaonuo.lh.modular.catalog.entity.GovAsset::getName, table)
                                    .or().eq(vip.xiaonuo.lh.modular.catalog.entity.GovAsset::getName, shortName)
                                    .or().eq(vip.xiaonuo.lh.modular.catalog.entity.GovAsset::getAssetCode, table)
                                    .or().like(vip.xiaonuo.lh.modular.catalog.entity.GovAsset::getOmFqn, shortName))
                            .last("LIMIT 3"));
            if (hits == null || hits.isEmpty()) {
                return null;
            }
            for (var a : hits) {
                if (table.equalsIgnoreCase(a.getName()) || shortName.equalsIgnoreCase(a.getName())
                        || table.equalsIgnoreCase(a.getAssetCode())) {
                    return a;
                }
            }
            return hits.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static String classifyImpactKind(String table, String etlJobId) {
        String t = StrUtil.blankToDefault(table, "").toLowerCase(Locale.ROOT);
        if (t.contains("metric") || t.contains("kpi") || t.startsWith("m_") || t.contains(".m_")) {
            return "指标";
        }
        if (t.contains("ads") || t.contains("report") || t.contains("dashboard") || t.contains("superset")) {
            return "报表";
        }
        if (StrUtil.isNotBlank(etlJobId) || t.contains("job") || t.contains("dag") || t.contains("etl")) {
            return "作业";
        }
        return guessLayer(table);
    }

    /**
     * 将 OM lineage payload 压成门户 graph 的 nodes/edges（有节点则目录可只读 OM）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> flattenOmLineageGraph(Object data, String focusKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (data == null) {
            out.put("nodes", nodes);
            out.put("edges", edges);
            return out;
        }
        Object nodesObj = mapGet(data, "nodes");
        if (nodesObj instanceof List<?> list) {
            for (Object o : list) {
                String id = entityNameOf(o);
                if (StrUtil.isBlank(id) || !seen.add(id)) {
                    continue;
                }
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", id);
                n.put("name", id);
                n.put("layer", guessLayer(id).toLowerCase(Locale.ROOT));
                n.put("type", "table");
                nodes.add(n);
            }
        }
        collectOmEdges(mapGet(data, "upstreamEdges"), edges, true);
        collectOmEdges(mapGet(data, "downstreamEdges"), edges, false);
        // 确保焦点在节点中
        if (StrUtil.isNotBlank(focusKey) && seen.add(normalizeTable(focusKey))) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", focusKey);
            n.put("name", focusKey);
            n.put("layer", guessLayer(focusKey).toLowerCase(Locale.ROOT));
            n.put("type", "table");
            n.put("focus", true);
            nodes.add(0, n);
        }
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    private static void collectOmEdges(Object edgesObj, List<Map<String, Object>> edges, boolean upstream) {
        if (!(edgesObj instanceof List<?> list)) {
            return;
        }
        int i = 0;
        for (Object o : list) {
            String from = firstNonBlank(entityNameOf(mapGet(o, "fromEntity")), entityNameOf(mapGet(o, "from")));
            String to = firstNonBlank(entityNameOf(mapGet(o, "toEntity")), entityNameOf(mapGet(o, "to")));
            // OM upstreamEdges: from=上游, to=焦点侧
            if (StrUtil.isBlank(from) || StrUtil.isBlank(to)) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", "om-" + (upstream ? "up" : "down") + "-" + (i++));
            e.put("from", from);
            e.put("to", to);
            e.put("source", "openmetadata");
            edges.add(e);
        }
    }

    private static Set<String> extractOmEntityNames(Object data, boolean upstream) {
        Set<String> out = new LinkedHashSet<>();
        Object edgesObj = mapGet(data, upstream ? "upstreamEdges" : "downstreamEdges");
        if (!(edgesObj instanceof List<?> list)) {
            // 退化：从 nodes 取
            Object nodesObj = mapGet(data, "nodes");
            if (nodesObj instanceof List<?> nodes) {
                for (Object n : nodes) {
                    String name = entityNameOf(n);
                    if (StrUtil.isNotBlank(name)) {
                        out.add(name);
                    }
                }
            }
            return out;
        }
        for (Object o : list) {
            String from = firstNonBlank(entityNameOf(mapGet(o, "fromEntity")), entityNameOf(mapGet(o, "from")));
            String to = firstNonBlank(entityNameOf(mapGet(o, "toEntity")), entityNameOf(mapGet(o, "to")));
            if (upstream) {
                if (StrUtil.isNotBlank(from)) {
                    out.add(from);
                }
            } else if (StrUtil.isNotBlank(to)) {
                out.add(to);
            }
        }
        return out;
    }

    private static Object mapGet(Object obj, String key) {
        if (obj instanceof Map<?, ?> m) {
            return m.get(key);
        }
        if (obj instanceof cn.hutool.json.JSONObject jo) {
            return jo.get(key);
        }
        return null;
    }

    private static String entityNameOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof CharSequence) {
            return String.valueOf(o);
        }
        Object fqn = mapGet(o, "fullyQualifiedName");
        if (fqn != null && StrUtil.isNotBlank(String.valueOf(fqn))) {
            return String.valueOf(fqn);
        }
        Object name = mapGet(o, "name");
        if (name != null && StrUtil.isNotBlank(String.valueOf(name))) {
            return String.valueOf(name);
        }
        Object id = mapGet(o, "id");
        return id == null ? null : String.valueOf(id);
    }

    private static String guessLayer(String table) {
        String t = StrUtil.blankToDefault(table, "").toLowerCase(Locale.ROOT);
        if (t.contains("ods")) return "ODS";
        if (t.contains("dwd")) return "DWD";
        if (t.contains("dws")) return "DWS";
        if (t.contains("ads")) return "ADS";
        if (t.contains("dim")) return "DIM";
        return "表";
    }

    private static String normalizeTable(String t) {
        return StrUtil.blankToDefault(t, "").trim();
    }

    private static String fieldKey(String table, String field) {
        return normalizeTable(table) + "." + StrUtil.blankToDefault(field, "");
    }

    private static int clamp(Integer v, int def) {
        if (v == null) return def;
        return Math.max(0, Math.min(v, 99));
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) return v;
        }
        return null;
    }
}
