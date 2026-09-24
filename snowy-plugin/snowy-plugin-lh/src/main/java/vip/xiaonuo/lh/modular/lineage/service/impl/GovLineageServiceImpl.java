package vip.xiaonuo.lh.modular.lineage.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
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
import vip.xiaonuo.lh.modular.lineage.entity.GovLineageChangeEval;
import vip.xiaonuo.lh.modular.lineage.entity.GovLineageDdlBlock;
import vip.xiaonuo.lh.modular.lineage.entity.GovLineageFieldEdge;
import vip.xiaonuo.lh.modular.lineage.mapper.CbLineageSyncWatermarkMapper;
import vip.xiaonuo.lh.modular.lineage.mapper.GovLineageChangeEvalMapper;
import vip.xiaonuo.lh.modular.lineage.mapper.GovLineageDdlBlockMapper;
import vip.xiaonuo.lh.modular.lineage.mapper.GovLineageFieldEdgeMapper;
import vip.xiaonuo.lh.modular.lineage.param.GovLineageEdgeUpsertParam;
import vip.xiaonuo.lh.modular.lineage.param.GovLineageIdParam;
import vip.xiaonuo.lh.modular.lineage.param.GovLineagePageParam;
import vip.xiaonuo.lh.modular.lineage.result.GovLineageEdgeVo;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.lineage.support.OmColumnLineageExtractor;

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
    private GovLineageChangeEvalMapper changeEvalMapper;
    @Resource
    private GovLineageDdlBlockMapper ddlBlockMapper;
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
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
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
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        String focusKey = firstNonBlank(omFqn, node, focus);
        if (StrUtil.isBlank(focusKey)) {
            focusKey = pickDefaultFocusTable(workspace);
        }
        List<GovLineageFieldEdge> all = listActiveEdges(workspace);

        Map<String, ExpandMeta> upMeta = expandTablesMeta(focusKey, all, true, up);
        Map<String, ExpandMeta> downMeta = expandTablesMeta(focusKey, all, false, down);
        upMeta.remove(normalizeTable(focusKey).toLowerCase(Locale.ROOT));
        downMeta.remove(normalizeTable(focusKey).toLowerCase(Locale.ROOT));

        Map<String, String> tableJob = new HashMap<>();
        for (GovLineageFieldEdge e : all) {
            if (StrUtil.isNotBlank(e.getEtlJobId()) && StrUtil.isNotBlank(e.getToTable())) {
                tableJob.putIfAbsent(normalizeTable(e.getToTable()), e.getEtlJobId());
            }
            if (StrUtil.isNotBlank(e.getEtlJobId()) && StrUtil.isNotBlank(e.getFromTable())) {
                tableJob.putIfAbsent(normalizeTable(e.getFromTable()), e.getEtlJobId());
            }
        }

        // OM 下游实体补充（有则并入；无门户路径时置信度按 explicit 登记）
        String impactSource = "portal_edges";
        Map<String, Object> om = openMetadataClient.getTableLineage(focusKey, up, down);
        if (Boolean.TRUE.equals(om.get("ok"))) {
            Set<String> omDown = extractOmEntityNames(om.get("data"), false);
            if (!omDown.isEmpty()) {
                mergeOmExpand(downMeta, omDown);
                impactSource = "openmetadata+portal_edges";
            }
            Set<String> omUp = extractOmEntityNames(om.get("data"), true);
            if (!omUp.isEmpty()) {
                mergeOmExpand(upMeta, omUp);
                impactSource = "openmetadata+portal_edges";
            }
            if (!omDown.isEmpty() || !omUp.isEmpty()) {
                if (all.isEmpty()) {
                    impactSource = "openmetadata";
                }
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("focus", focusKey);
        r.put("source", impactSource);
        r.put("up", upMeta.values().stream()
                .map(m -> impactItem(m, "上游", tableJob.get(normalizeTable(m.table)), workspace))
                .collect(Collectors.toList()));
        r.put("down", downMeta.values().stream()
                .map(m -> impactItem(m, "下游", tableJob.get(normalizeTable(m.table)), workspace))
                .collect(Collectors.toList()));
        r.put("upCount", upMeta.size());
        r.put("downCount", downMeta.size());
        return r;
    }

    @Override
    public Page<GovLineageEdgeVo> pageFields(GovLineagePageParam param) {
        QueryWrapper<GovLineageFieldEdge> qw = new QueryWrapper<>();
        qw.lambda().eq(GovLineageFieldEdge::getDeleteFlag, NOT_DELETE);
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(param.getWs());
        qw.lambda().eq(StrUtil.isNotBlank(workspace), GovLineageFieldEdge::getWs, workspace);
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
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        List<GovLineageFieldEdge> rows = edgeMapper.selectList(new QueryWrapper<GovLineageFieldEdge>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLineageFieldEdge::getWs, workspace)
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
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        String markKey = "ws:" + workspace + (StrUtil.isBlank(etlJobId) ? "" : (":job:" + etlJobId));
        String markValue = String.valueOf(System.currentTimeMillis());

        // 重扫 ETL DAG：fieldMaps → 显式字段边；拓扑 → 推断 *→* 边
        int dagCount = 0;
        int lineageOk = 0;
        int lineageFail = 0;
        int topologyOk = 0;
        List<String> notes = new ArrayList<>();
        QueryWrapper<IgEtlDag> dqw = new QueryWrapper<>();
        dqw.lambda().eq(StrUtil.isNotBlank(workspace), IgEtlDag::getWs, workspace);
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

        Map<String, Object> omMerge = mergeOmColumnEdges(workspace);
        if (Boolean.TRUE.equals(omMerge.get("ok")) || toInt(omMerge.get("merged")) > 0) {
            upsertWatermark("om", markKey, markValue);
        }

        long edges = edgeMapper.selectCount(new QueryWrapper<GovLineageFieldEdge>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLineageFieldEdge::getWs, workspace)
                .eq(GovLineageFieldEdge::getDeleteFlag, NOT_DELETE));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("ws", workspace);
        r.put("dagCount", dagCount);
        r.put("lineageOk", lineageOk);
        r.put("lineageFail", lineageFail);
        r.put("topologyOk", topologyOk);
        r.put("omColumnMerged", omMerge.get("merged"));
        r.put("omColumnTables", omMerge.get("tables"));
        r.put("omColumnDegraded", omMerge.get("degraded"));
        r.put("edgeCount", edges);
        r.put("markKey", markKey);
        r.put("markValue", markValue);
        r.put("marquez", mz);
        r.put("hint", "已重扫 ETL fieldMaps/拓扑 + OM 列级合并写入 gov_lineage_field_edge；Marquez 作业血缘待全量投影");
        List<String> allNotes = new ArrayList<>(notes);
        Object omNotes = omMerge.get("notes");
        if (omNotes instanceof List<?> list) {
            for (Object o : list) {
                if (allNotes.size() < 12) {
                    allNotes.add(String.valueOf(o));
                }
            }
        }
        if (!allNotes.isEmpty()) {
            r.put("notes", allNotes);
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
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
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
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> changeEval(String table, String field, String toType, String ws) {
        if (StrUtil.isBlank(table) || StrUtil.isBlank(field)) {
            throw new CommonException("table/field 不能为空");
        }
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<GovLineageFieldEdge> all = listActiveEdges(workspace);
        List<Map<String, Object>> downstream = propagateFields(table, field, all, 8);
        boolean noImpact = downstream.isEmpty();
        boolean typeBlank = StrUtil.isBlank(toType);
        boolean passed = noImpact || typeBlank;
        String status = passed ? "approved" : "pending";
        String id = IdUtil.getSnowflakeNextIdStr();
        String ticketId = "LE-" + id.substring(Math.max(0, id.length() - 10));
        String message = noImpact
                ? "暂无下游字段边"
                : ("影响 " + downstream.size() + " 个下游字段对象"
                + (passed ? "" : " · 待审批"));

        Date now = new Date();
        GovLineageChangeEval row = new GovLineageChangeEval();
        row.setId(id);
        row.setRevision(1);
        row.setWs(workspace);
        row.setTableName(table.trim());
        row.setFieldName(field.trim());
        row.setToType(toType);
        row.setImpactCount(downstream.size());
        row.setImpactJson(JSONUtil.toJsonStr(downstream.size() > 40 ? downstream.subList(0, 40) : downstream));
        row.setStatus(status);
        row.setPassed(passed ? 1 : 0);
        row.setTicketId(ticketId);
        row.setMessage(message);
        row.setDeleteFlag(NOT_DELETE);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        changeEvalMapper.insert(row);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("ticketId", ticketId);
        r.put("table", table);
        r.put("field", field);
        r.put("toType", toType);
        r.put("impactCount", downstream.size());
        r.put("downstream", downstream);
        r.put("passed", passed);
        r.put("status", status);
        r.put("message", message);
        r.put("persisted", true);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> decideChangeEval(String id, String decision, String ws) {
        if (StrUtil.isBlank(id)) {
            throw new CommonException("id 不能为空");
        }
        String dec = StrUtil.blankToDefault(decision, "").trim().toLowerCase(Locale.ROOT);
        if (!"approved".equals(dec) && !"rejected".equals(dec)) {
            throw new CommonException("decision 须为 approved 或 rejected");
        }
        GovLineageChangeEval row = changeEvalMapper.selectById(id);
        if (row == null || !NOT_DELETE.equals(row.getDeleteFlag())) {
            throw new CommonException("评估单不存在");
        }
        if (StrUtil.isNotBlank(ws) && !StrUtil.blankToDefault(ws, WS_DEFAULT).equals(row.getWs())) {
            throw new CommonException("评估单不属于该工作空间");
        }
        Date now = new Date();
        row.setStatus(dec);
        row.setPassed("approved".equals(dec) ? 1 : 0);
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        row.setUpdateTime(now);
        row.setMessage(("approved".equals(dec) ? "审批通过" : "审批驳回")
                + " · " + StrUtil.blankToDefault(row.getMessage(), ""));
        changeEvalMapper.updateById(row);

        if ("approved".equals(dec)) {
            // 通过后关闭同表字段上的活跃阻断
            List<GovLineageDdlBlock> blocks = ddlBlockMapper.selectList(new QueryWrapper<GovLineageDdlBlock>().lambda()
                    .eq(GovLineageDdlBlock::getWs, row.getWs())
                    .eq(GovLineageDdlBlock::getTableName, row.getTableName())
                    .eq(GovLineageDdlBlock::getActive, 1)
                    .eq(GovLineageDdlBlock::getDeleteFlag, NOT_DELETE)
                    .and(w -> w.eq(GovLineageDdlBlock::getFieldName, row.getFieldName())
                            .or().isNull(GovLineageDdlBlock::getFieldName)
                            .or().eq(GovLineageDdlBlock::getFieldName, "")));
            for (GovLineageDdlBlock b : blocks) {
                b.setActive(0);
                b.setUpdateTime(now);
                b.setRevision(b.getRevision() == null ? 1 : b.getRevision() + 1);
                ddlBlockMapper.updateById(b);
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", row.getId());
        r.put("ticketId", row.getTicketId());
        r.put("status", row.getStatus());
        r.put("passed", Integer.valueOf(1).equals(row.getPassed()));
        r.put("table", row.getTableName());
        r.put("field", row.getFieldName());
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> blockDdl(String table, String field, String reason, String ws) {
        if (StrUtil.isBlank(table)) {
            throw new CommonException("table 不能为空");
        }
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        String reasonText = StrUtil.blankToDefault(reason, "变更评估未通过");
        String ticketId = IdUtil.getSnowflakeNextIdStr();
        Date now = new Date();

        // 关联最近一张未删评估单（同表字段）
        String evalId = null;
        if (StrUtil.isNotBlank(field)) {
            GovLineageChangeEval latest = changeEvalMapper.selectOne(new QueryWrapper<GovLineageChangeEval>().lambda()
                    .eq(GovLineageChangeEval::getWs, workspace)
                    .eq(GovLineageChangeEval::getTableName, table.trim())
                    .eq(GovLineageChangeEval::getFieldName, field.trim())
                    .eq(GovLineageChangeEval::getDeleteFlag, NOT_DELETE)
                    .orderByDesc(GovLineageChangeEval::getCreateTime)
                    .last("LIMIT 1"));
            if (latest != null) {
                evalId = latest.getId();
                if (StrUtil.isNotBlank(latest.getTicketId())) {
                    ticketId = latest.getTicketId();
                }
            }
        }

        GovLineageDdlBlock existing = ddlBlockMapper.selectOne(new QueryWrapper<GovLineageDdlBlock>().lambda()
                .eq(GovLineageDdlBlock::getWs, workspace)
                .eq(GovLineageDdlBlock::getTableName, table.trim())
                .eq(GovLineageDdlBlock::getFieldName, StrUtil.blankToDefault(field, ""))
                .eq(GovLineageDdlBlock::getDeleteFlag, NOT_DELETE)
                .eq(GovLineageDdlBlock::getActive, 1)
                .last("LIMIT 1"));
        if (existing == null) {
            existing = new GovLineageDdlBlock();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setRevision(1);
            existing.setWs(workspace);
            existing.setTableName(table.trim());
            existing.setFieldName(StrUtil.blankToDefault(field, ""));
            existing.setDeleteFlag(NOT_DELETE);
            existing.setCreateTime(now);
            ddlBlockMapper.insert(existing);
        } else {
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
        }
        existing.setReason(reasonText);
        existing.setActive(1);
        existing.setEvalId(evalId);
        existing.setTicketId(ticketId);
        existing.setUpdateTime(now);
        ddlBlockMapper.updateById(existing);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("blocked", true);
        r.put("id", existing.getId());
        r.put("table", table);
        r.put("field", field);
        r.put("reason", reasonText);
        r.put("ws", workspace);
        r.put("evalId", evalId);
        r.put("ticketId", ticketId);
        r.put("persisted", true);
        r.put("hint", "已落库 gov_lineage_ddl_block；发布门禁 gate5 将阻断命中表");
        return r;
    }

    @Override
    public Map<String, Object> assessPublishGate(String ws, String tableHint) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        String table = StrUtil.trim(tableHint);
        List<GovLineageDdlBlock> active = ddlBlockMapper.selectList(new QueryWrapper<GovLineageDdlBlock>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLineageDdlBlock::getWs, workspace)
                .eq(GovLineageDdlBlock::getActive, 1)
                .eq(GovLineageDdlBlock::getDeleteFlag, NOT_DELETE));
        if (active.isEmpty()) {
            // 无活跃阻断：若有待审评估且表匹配则仍 fail
            if (StrUtil.isNotBlank(table)) {
                List<GovLineageChangeEval> pendingRows = changeEvalMapper.selectList(
                        new QueryWrapper<GovLineageChangeEval>().lambda()
                                .eq(StrUtil.isNotBlank(workspace), GovLineageChangeEval::getWs, workspace)
                                .eq(GovLineageChangeEval::getStatus, "pending")
                                .eq(GovLineageChangeEval::getDeleteFlag, NOT_DELETE));
                for (GovLineageChangeEval e : pendingRows) {
                    if (tableMatches(e.getTableName(), table)) {
                        Map<String, Object> fail = new LinkedHashMap<>();
                        fail.put("status", "fail");
                        fail.put("detail", "变更评估待审 pending · " + e.getTableName() + "." + e.getFieldName()
                                + " · " + e.getTicketId());
                        fail.put("blocked", true);
                        fail.put("evalId", e.getId());
                        return fail;
                    }
                }
            }
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("status", "skip");
            skip.put("detail", "无活跃 DDL 阻断");
            skip.put("blocked", false);
            return skip;
        }
        for (GovLineageDdlBlock b : active) {
            if (StrUtil.isBlank(table) || tableMatches(b.getTableName(), table)) {
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("status", "fail");
                fail.put("detail", "血缘 DDL 阻断生效 · " + b.getTableName()
                        + (StrUtil.isNotBlank(b.getFieldName()) ? ("." + b.getFieldName()) : "")
                        + " · " + StrUtil.blankToDefault(b.getReason(), "变更评估未通过"));
                fail.put("blocked", true);
                fail.put("blockId", b.getId());
                fail.put("ticketId", b.getTicketId());
                return fail;
            }
        }
        Map<String, Object> pass = new LinkedHashMap<>();
        pass.put("status", "pass");
        pass.put("detail", "有阻断登记但未命中当前脚本表（active=" + active.size() + "）");
        pass.put("blocked", false);
        return pass;
    }

    @Override
    public Map<String, Object> assessLineageIngestGate(String ws, String tableHint) {
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        String table = StrUtil.trim(tableHint);
        long total = edgeMapper.selectCount(new QueryWrapper<GovLineageFieldEdge>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLineageFieldEdge::getWs, workspace)
                .eq(GovLineageFieldEdge::getDeleteFlag, NOT_DELETE));
        if (total <= 0) {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("status", "skip");
            skip.put("detail", "无门户字段边");
            skip.put("blocked", false);
            return skip;
        }
        if (StrUtil.isBlank(table)) {
            Map<String, Object> pass = new LinkedHashMap<>();
            pass.put("status", "pass");
            pass.put("detail", "已有字段边 " + total + " 条");
            pass.put("blocked", false);
            pass.put("edgeCount", total);
            return pass;
        }
        long matched = edgeMapper.selectCount(new QueryWrapper<GovLineageFieldEdge>().lambda()
                .eq(StrUtil.isNotBlank(workspace), GovLineageFieldEdge::getWs, workspace)
                .eq(GovLineageFieldEdge::getDeleteFlag, NOT_DELETE)
                .and(w -> w.like(GovLineageFieldEdge::getFromTable, table)
                        .or().like(GovLineageFieldEdge::getToTable, table)));
        if (matched > 0) {
            Map<String, Object> pass = new LinkedHashMap<>();
            pass.put("status", "pass");
            pass.put("detail", "脚本相关字段边 " + matched + " 条");
            pass.put("blocked", false);
            pass.put("edgeCount", matched);
            return pass;
        }
        Map<String, Object> skip = new LinkedHashMap<>();
        skip.put("status", "skip");
        skip.put("detail", "字段边未覆盖当前脚本表（总边=" + total + "）");
        skip.put("blocked", false);
        return skip;
    }

    @Override
    public Map<String, Object> marquezNamespaces() {
        return marquezClient.listNamespaces();
    }

    /**
     * soft-fail：对门户边涉及的表拉取 OM 表级 lineage，投影 columnsLineage → gov_lineage_field_edge(etl_job_id=om_column)。
     */
    private Map<String, Object> mergeOmColumnEdges(String workspace) {
        Map<String, Object> r = new LinkedHashMap<>();
        int merged = 0;
        int tables = 0;
        boolean degraded = false;
        List<String> notes = new ArrayList<>();
        Set<String> focusFqns = new LinkedHashSet<>();
        for (GovLineageFieldEdge e : listActiveEdges(workspace)) {
            if (StrUtil.isNotBlank(e.getOmFromFqn())) {
                String[] p = OmColumnLineageExtractor.splitColumnFqn(e.getOmFromFqn());
                if (p != null) {
                    focusFqns.add(p[0]);
                }
            }
            if (StrUtil.isNotBlank(e.getOmToFqn())) {
                String[] p = OmColumnLineageExtractor.splitColumnFqn(e.getOmToFqn());
                if (p != null) {
                    focusFqns.add(p[0]);
                }
            }
            if (StrUtil.isNotBlank(e.getFromTable()) && !"*".equals(e.getFromField())) {
                focusFqns.add(e.getFromTable());
            }
            if (StrUtil.isNotBlank(e.getToTable()) && !"*".equals(e.getToField())) {
                focusFqns.add(e.getToTable());
            }
        }
        // 限流：最多扫 12 张表
        int n = 0;
        for (String fqn : focusFqns) {
            if (n++ >= 12) {
                notes.add("om_column: 表数截断至 12");
                break;
            }
            tables++;
            try {
                Map<String, Object> om = openMetadataClient.getTableLineage(fqn, 2, 2);
                if (!Boolean.TRUE.equals(om.get("ok"))) {
                    degraded = true;
                    if (notes.size() < 6) {
                        notes.add("om soft-fail " + fqn + ": " + om.get("message"));
                    }
                    continue;
                }
                List<Map<String, String>> cols = OmColumnLineageExtractor.extractColumnEdges(om.get("data"));
                for (Map<String, String> c : cols) {
                    GovLineageEdgeUpsertParam p = new GovLineageEdgeUpsertParam();
                    p.setWs(workspace);
                    p.setFromTable(c.get("fromTable"));
                    p.setFromField(c.get("fromField"));
                    p.setToTable(c.get("toTable"));
                    p.setToField(c.get("toField"));
                    p.setTransformText(c.get("transform"));
                    p.setConfidence("explicit");
                    p.setEtlJobId(OmColumnLineageExtractor.ETL_JOB_OM_COLUMN);
                    p.setOmFromFqn(c.get("omFromFqn"));
                    p.setOmToFqn(c.get("omToFqn"));
                    p.setRemark("om_column_merge");
                    upsertField(p);
                    merged++;
                }
            } catch (Exception ex) {
                degraded = true;
                if (notes.size() < 6) {
                    notes.add("om merge " + fqn + ": " + ex.getMessage());
                }
            }
        }
        r.put("ok", !degraded || merged > 0);
        r.put("merged", merged);
        r.put("tables", tables);
        r.put("degraded", degraded);
        if (!notes.isEmpty()) {
            r.put("notes", notes);
        }
        return r;
    }

    private static boolean tableMatches(String stored, String hint) {
        if (StrUtil.isBlank(hint)) {
            return true;
        }
        if (StrUtil.isBlank(stored)) {
            return false;
        }
        String a = stored.toLowerCase(Locale.ROOT);
        String b = hint.toLowerCase(Locale.ROOT);
        return a.equals(b) || a.endsWith("." + b) || a.contains(b) || b.contains(a);
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
        Set<String> out = new LinkedHashSet<>();
        for (ExpandMeta m : expandTablesMeta(focus, all, upstream, depth).values()) {
            out.add(m.table);
        }
        return out;
    }

    /**
     * BFS 展开：按最短路径记录 hop；路径上任一边为 inferred 则整表 confidence=inferred。
     * key = 表名小写；含焦点自身（hop=0）。
     */
    private Map<String, ExpandMeta> expandTablesMeta(String focus, List<GovLineageFieldEdge> all,
                                                     boolean upstream, int depth) {
        String start = normalizeTable(focus);
        Map<String, ExpandMeta> byNorm = new LinkedHashMap<>();
        Queue<String> q = new ArrayDeque<>();
        ExpandMeta startMeta = new ExpandMeta(start, 0, "explicit");
        byNorm.put(start.toLowerCase(Locale.ROOT), startMeta);
        q.add(start);
        while (!q.isEmpty()) {
            String cur = q.poll();
            ExpandMeta curMeta = byNorm.get(cur.toLowerCase(Locale.ROOT));
            int h = curMeta == null ? 0 : curMeta.hop;
            if (h >= depth) {
                continue;
            }
            for (GovLineageFieldEdge e : all) {
                String from = normalizeTable(e.getFromTable());
                String to = normalizeTable(e.getToTable());
                String next = upstream ? (to.equalsIgnoreCase(cur) ? from : null)
                        : (from.equalsIgnoreCase(cur) ? to : null);
                if (next == null) {
                    continue;
                }
                String nk = next.toLowerCase(Locale.ROOT);
                if (byNorm.containsKey(nk)) {
                    continue;
                }
                String conf = worseConfidence(curMeta == null ? "explicit" : curMeta.confidence, e.getConfidence());
                ExpandMeta nextMeta = new ExpandMeta(next, h + 1, conf);
                byNorm.put(nk, nextMeta);
                q.add(next);
            }
        }
        return byNorm;
    }

    private static void mergeOmExpand(Map<String, ExpandMeta> meta, Set<String> names) {
        for (String t : names) {
            if (StrUtil.isBlank(t)) {
                continue;
            }
            String nk = normalizeTable(t).toLowerCase(Locale.ROOT);
            meta.putIfAbsent(nk, new ExpandMeta(t, 1, "explicit"));
        }
    }

    private static String worseConfidence(String a, String b) {
        String x = StrUtil.blankToDefault(a, "explicit").trim().toLowerCase(Locale.ROOT);
        String y = StrUtil.blankToDefault(b, "explicit").trim().toLowerCase(Locale.ROOT);
        if ("inferred".equals(x) || "inferred".equals(y)) {
            return "inferred";
        }
        return "explicit";
    }

    private static final class ExpandMeta {
        final String table;
        final int hop;
        final String confidence;

        ExpandMeta(String table, int hop, String confidence) {
            this.table = table;
            this.hop = hop;
            this.confidence = confidence;
        }
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
                .eq(StrUtil.isNotBlank(ws), GovLineageFieldEdge::getWs, ws)
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

    private Map<String, Object> impactItem(ExpandMeta meta, String dir, String etlJobId, String ws) {
        Map<String, Object> m = impactItem(meta.table, dir, etlJobId, ws);
        m.put("confidence", StrUtil.blankToDefault(meta.confidence, "explicit"));
        m.put("hop", meta.hop);
        return m;
    }

    private Map<String, Object> impactItem(String table, String dir, String etlJobId, String ws) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", table);
        String kind = classifyImpactKind(table, etlJobId);
        m.put("type", kind);
        m.put("layer", guessLayer(table));
        m.put("note", dir + "依赖");
        m.put("confidence", "explicit");
        m.put("hop", null);

        vip.xiaonuo.lh.modular.catalog.entity.GovAsset asset = findAssetByTable(table, ws);
        if (asset != null) {
            m.put("assetId", asset.getId());
            m.put("assetCode", asset.getAssetCode());
            String owner = firstNonBlank(asset.getTechOwner(), asset.getBizOwner());
            m.put("owner", owner);
            m.put("techOwner", asset.getTechOwner());
            m.put("bizOwner", asset.getBizOwner());
            m.put("sensitivity", asset.getSensitivity());
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
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        try {
            List<vip.xiaonuo.lh.modular.catalog.entity.GovAsset> hits =
                    govAssetMapper.selectList(new QueryWrapper<vip.xiaonuo.lh.modular.catalog.entity.GovAsset>().lambda()
                            .eq(StrUtil.isNotBlank(workspace), vip.xiaonuo.lh.modular.catalog.entity.GovAsset::getWs, workspace)
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
