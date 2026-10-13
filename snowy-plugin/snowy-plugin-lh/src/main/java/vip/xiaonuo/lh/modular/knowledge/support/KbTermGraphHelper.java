package vip.xiaonuo.lh.modular.knowledge.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.lh.modular.knowledge.entity.GovKbEntry;
import vip.xiaonuo.lh.modular.knowledge.entity.GovKbTermEdge;
import vip.xiaonuo.lh.modular.knowledge.mapper.GovKbEntryMapper;
import vip.xiaonuo.lh.modular.knowledge.mapper.GovKbTermEdgeMapper;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMapper;
import vip.xiaonuo.lh.modular.standard.entity.GovStdCode;
import vip.xiaonuo.lh.modular.standard.entity.GovStdField;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdCodeMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdFieldMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 术语边物化 + 只读图谱/治理 KPI。
 */
@Component
public class KbTermGraphHelper {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String CAT_TERM = "term";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_BROKEN = "broken";

    @Resource
    private GovKbTermEdgeMapper edgeMapper;
    @Resource
    private GovKbEntryMapper entryMapper;
    @Resource
    private GovMetricMapper metricMapper;
    @Resource
    private GovStdFieldMapper stdFieldMapper;
    @Resource
    private GovStdCodeMapper stdCodeMapper;

    @Transactional(rollbackFor = Exception.class)
    public void syncEdgesForEntry(GovKbEntry entry) {
        if (entry == null || StrUtil.isBlank(entry.getId())) {
            return;
        }
        clearEdges(entry.getId());
        if (!CAT_TERM.equalsIgnoreCase(StrUtil.nullToEmpty(entry.getCat()))) {
            return;
        }
        List<KbTermRefs.EdgeDraft> drafts = KbTermRefs.extractEdges(
                entry.getId(), entry.getRefsJson(), entry.getWs());
        Date now = new Date();
        for (KbTermRefs.EdgeDraft d : drafts) {
            GovKbTermEdge e = new GovKbTermEdge();
            e.setId(IdUtil.getSnowflakeNextIdStr());
            e.setRevision(1);
            e.setWs(StrUtil.blankToDefault(d.ws(), "default"));
            e.setFromEntryId(d.fromEntryId());
            e.setEdgeType(d.edgeType());
            e.setToKind(d.toKind());
            e.setToId(d.toId());
            e.setToLabel(resolveLabel(d));
            e.setStatus(resolveStatus(d));
            e.setDeleteFlag(NOT_DELETE);
            e.setCreateTime(now);
            e.setUpdateTime(now);
            edgeMapper.insert(e);
        }
    }

    public void clearEdges(String entryId) {
        if (StrUtil.isBlank(entryId)) {
            return;
        }
        edgeMapper.delete(new QueryWrapper<GovKbTermEdge>().lambda()
                .eq(GovKbTermEdge::getFromEntryId, entryId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rebuildAllEdges() {
        List<GovKbEntry> terms = entryMapper.selectList(new QueryWrapper<GovKbEntry>().lambda()
                .eq(GovKbEntry::getDeleteFlag, NOT_DELETE)
                .eq(GovKbEntry::getCat, CAT_TERM));
        int n = 0;
        for (GovKbEntry t : terms) {
            syncEdgesForEntry(t);
            n++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("termEntries", n);
        out.put("edgeCount", edgeMapper.selectCount(new QueryWrapper<GovKbTermEdge>().lambda()
                .eq(GovKbTermEdge::getDeleteFlag, NOT_DELETE)));
        return out;
    }

    public Map<String, Object> graph(String rootEntryId, int depth) {
        int d = Math.max(1, Math.min(depth <= 0 ? 2 : depth, 3));
        List<GovKbTermEdge> allEdges = edgeMapper.selectList(new QueryWrapper<GovKbTermEdge>().lambda()
                .eq(GovKbTermEdge::getDeleteFlag, NOT_DELETE));
        Map<String, GovKbEntry> termById = loadTermMap();
        Set<String> nodeIds = new LinkedHashSet<>();
        List<Map<String, Object>> edgeRows = new ArrayList<>();

        if (StrUtil.isNotBlank(rootEntryId) && termById.containsKey(rootEntryId)) {
            Set<String> frontier = new LinkedHashSet<>();
            frontier.add(rootEntryId.trim());
            nodeIds.add(rootEntryId.trim());
            for (int i = 0; i < d; i++) {
                Set<String> next = new LinkedHashSet<>();
                for (GovKbTermEdge e : allEdges) {
                    if (!frontier.contains(e.getFromEntryId())) {
                        continue;
                    }
                    edgeRows.add(edgeVo(e));
                    String nid = nodeKey(e.getToKind(), e.getToId());
                    nodeIds.add(e.getFromEntryId());
                    nodeIds.add(nid);
                    if (KbTermRefs.KIND_TERM.equals(e.getToKind())) {
                        next.add(e.getToId());
                    }
                }
                // reverse: terms pointing to frontier
                for (GovKbTermEdge e : allEdges) {
                    if (KbTermRefs.KIND_TERM.equals(e.getToKind()) && frontier.contains(e.getToId())) {
                        edgeRows.add(edgeVo(e));
                        nodeIds.add(e.getFromEntryId());
                        nodeIds.add(nodeKey(e.getToKind(), e.getToId()));
                        next.add(e.getFromEntryId());
                    }
                }
                frontier = next;
            }
        } else {
            for (GovKbTermEdge e : allEdges) {
                edgeRows.add(edgeVo(e));
                nodeIds.add(e.getFromEntryId());
                nodeIds.add(nodeKey(e.getToKind(), e.getToId()));
            }
            for (String tid : termById.keySet()) {
                nodeIds.add(tid);
            }
        }

        // dedupe edges
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        for (Map<String, Object> er : edgeRows) {
            String k = er.get("fromId") + "|" + er.get("edgeType") + "|" + er.get("toId");
            uniq.putIfAbsent(k, er);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String nid : nodeIds) {
            if (!seen.add(nid)) {
                continue;
            }
            nodes.add(nodeVo(nid, termById, allEdges));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", new ArrayList<>(uniq.values()));
        out.put("root", StrUtil.blankToDefault(rootEntryId, ""));
        out.put("depth", d);
        out.put("sot", "gov_kb_entry.term");
        return out;
    }

    public Map<String, Object> glossaryStats() {
        List<GovKbEntry> terms = entryMapper.selectList(new QueryWrapper<GovKbEntry>().lambda()
                .eq(GovKbEntry::getDeleteFlag, NOT_DELETE)
                .eq(GovKbEntry::getCat, CAT_TERM));
        List<GovKbTermEdge> edges = edgeMapper.selectList(new QueryWrapper<GovKbTermEdge>().lambda()
                .eq(GovKbTermEdge::getDeleteFlag, NOT_DELETE));

        long withMetric = terms.stream().filter(t ->
                edges.stream().anyMatch(e ->
                        e.getFromEntryId().equals(t.getId())
                                && KbTermRefs.EDGE_METRIC.equals(e.getEdgeType()))).count();
        long withStd = terms.stream().filter(t ->
                edges.stream().anyMatch(e ->
                        e.getFromEntryId().equals(t.getId())
                                && KbTermRefs.EDGE_STD.equals(e.getEdgeType()))).count();
        long withAsset = terms.stream().filter(t ->
                edges.stream().anyMatch(e ->
                        e.getFromEntryId().equals(t.getId())
                                && KbTermRefs.EDGE_ASSET.equals(e.getEdgeType()))).count();
        long withSynonym = terms.stream().filter(t ->
                edges.stream().anyMatch(e ->
                        e.getFromEntryId().equals(t.getId())
                                && KbTermRefs.EDGE_SYNONYM.equals(e.getEdgeType()))).count();
        long linked = terms.stream().filter(t ->
                edges.stream().anyMatch(e -> e.getFromEntryId().equals(t.getId()))).count();
        long broken = edges.stream().filter(e -> STATUS_BROKEN.equalsIgnoreCase(e.getStatus())).count();

        List<GovMetric> activeMetrics = metricMapper.selectList(new QueryWrapper<GovMetric>().lambda()
                .eq(GovMetric::getDeleteFlag, NOT_DELETE)
                .eq(GovMetric::getStatus, "active"));
        Set<String> coveredCodes = edges.stream()
                .filter(e -> KbTermRefs.EDGE_METRIC.equals(e.getEdgeType()))
                .map(e -> e.getToId().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        long metricCovered = activeMetrics.stream()
                .filter(m -> m.getMetricCode() != null
                        && coveredCodes.contains(m.getMetricCode().toLowerCase(Locale.ROOT)))
                .count();
        List<String> uncovered = activeMetrics.stream()
                .filter(m -> m.getMetricCode() != null
                        && !coveredCodes.contains(m.getMetricCode().toLowerCase(Locale.ROOT)))
                .map(GovMetric::getMetricCode)
                .limit(50)
                .toList();

        double termLinkRate = terms.isEmpty() ? 0 : (linked * 100.0 / terms.size());
        double metricCoverage = activeMetrics.isEmpty() ? 0
                : (metricCovered * 100.0 / activeMetrics.size());
        double brokenRate = edges.isEmpty() ? 0 : (broken * 100.0 / edges.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("termCount", terms.size());
        out.put("edgeCount", edges.size());
        out.put("termsWithLink", linked);
        out.put("termsWithMetric", withMetric);
        out.put("termsWithStandard", withStd);
        out.put("termsWithAsset", withAsset);
        out.put("termsWithSynonym", withSynonym);
        out.put("termLinkRatePct", round1(termLinkRate));
        out.put("brokenEdgeCount", broken);
        out.put("brokenEdgeRatePct", round1(brokenRate));
        out.put("activeMetricCount", activeMetrics.size());
        out.put("metricWithTermCount", metricCovered);
        out.put("metricTermCoveragePct", round1(metricCoverage));
        out.put("uncoveredMetricCodes", uncovered);
        out.put("sot", "gov_kb_entry.term");
        out.put("omGlossaryRole", "mirror_only_standard_metric");
        return out;
    }

    /**
     * 是否存在有效术语边：implements_metric → metricCode 且 status≠broken。
     */
    public boolean hasTermForMetric(String metricCode) {
        return findTermEdgeByMetricCode(metricCode) != null;
    }

    public GovKbTermEdge findTermEdgeByMetricCode(String metricCode) {
        if (StrUtil.isBlank(metricCode)) {
            return null;
        }
        String code = metricCode.trim();
        List<GovKbTermEdge> edges = edgeMapper.selectList(new QueryWrapper<GovKbTermEdge>().lambda()
                .eq(GovKbTermEdge::getDeleteFlag, NOT_DELETE)
                .eq(GovKbTermEdge::getEdgeType, KbTermRefs.EDGE_METRIC)
                .eq(GovKbTermEdge::getToKind, KbTermRefs.KIND_METRIC));
        for (GovKbTermEdge e : edges) {
            if (e == null || StrUtil.isBlank(e.getToId())) {
                continue;
            }
            if (!code.equalsIgnoreCase(e.getToId().trim())) {
                continue;
            }
            if (STATUS_BROKEN.equalsIgnoreCase(e.getStatus())) {
                continue;
            }
            return e;
        }
        return null;
    }

    public GovKbEntry findTermByMetricCode(String metricCode) {
        GovKbTermEdge edge = findTermEdgeByMetricCode(metricCode);
        if (edge == null) {
            return null;
        }
        GovKbEntry entry = entryMapper.selectById(edge.getFromEntryId());
        if (entry == null || !NOT_DELETE.equals(entry.getDeleteFlag())) {
            return null;
        }
        if (!CAT_TERM.equalsIgnoreCase(entry.getCat())) {
            return null;
        }
        return entry;
    }

    /**
     * 幂等：已有 implements_metric 边则返回已有术语；否则新建 cat=term 条目并物化边。
     * 不修改 gov_metric 口径公式。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ensureTermForMetric(GovMetric metric) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (metric == null || StrUtil.isBlank(metric.getMetricCode())) {
            out.put("created", false);
            out.put("message", "metricCode 为空");
            return out;
        }
        String code = metric.getMetricCode().trim();
        GovKbEntry existing = findTermByMetricCode(code);
        if (existing != null) {
            out.put("created", false);
            out.put("entryId", existing.getId());
            out.put("title", existing.getTitle());
            out.put("metricCode", code);
            out.put("message", "术语已存在");
            return out;
        }
        String title = StrUtil.blankToDefault(metric.getName(), code);
        String body = "业务术语（由指标中心 ensure-term 生成）\n"
                + "metric_code: " + code + "\n"
                + "名称: " + title + "\n"
                + "口径公式以指标中心为准，本条目仅作 Business Glossary 解释与关联。";
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("metricCode", code);
        refs.put("link", "/metric?code=" + code);
        refs.put("note", "ensure-term");
        GovKbEntry row = new GovKbEntry();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setRevision(1);
        row.setStatus("ready");
        row.setScope("workspace");
        row.setWs(StrUtil.blankToDefault(metric.getWs(), "default"));
        row.setCat(CAT_TERM);
        row.setTitle(title + "（" + code + "）");
        row.setBody(body);
        row.setSource("manual");
        row.setStrategy("paragraph");
        row.setChunkSize(500);
        row.setOverlap(50);
        row.setRefsJson(cn.hutool.json.JSONUtil.toJsonStr(refs));
        row.setCiteCnt(0);
        row.setLifecycleStatus("active");
        row.setDeleteFlag(NOT_DELETE);
        Date now = new Date();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        entryMapper.insert(row);
        syncEdgesForEntry(row);
        out.put("created", true);
        out.put("entryId", row.getId());
        out.put("title", row.getTitle());
        out.put("metricCode", code);
        out.put("message", "已创建业务术语");
        return out;
    }

    private String resolveStatus(KbTermRefs.EdgeDraft d) {
        if (KbTermRefs.KIND_METRIC.equals(d.toKind())) {
            Long c = metricMapper.selectCount(new QueryWrapper<GovMetric>().lambda()
                    .eq(GovMetric::getDeleteFlag, NOT_DELETE)
                    .eq(GovMetric::getMetricCode, d.toId()));
            return c != null && c > 0 ? STATUS_ACTIVE : STATUS_BROKEN;
        }
        if (KbTermRefs.KIND_STD.equals(d.toKind())) {
            if (existsStandard(d.toId())) {
                return STATUS_ACTIVE;
            }
            return STATUS_BROKEN;
        }
        if (KbTermRefs.KIND_TERM.equals(d.toKind())) {
            GovKbEntry t = entryMapper.selectById(d.toId());
            if (t != null && NOT_DELETE.equals(t.getDeleteFlag())
                    && CAT_TERM.equalsIgnoreCase(t.getCat())) {
                return STATUS_ACTIVE;
            }
            return STATUS_BROKEN;
        }
        // asset / label：不硬校验资产 FQN（避免跨 Catalog 探活）；同义词恒 active
        return STATUS_ACTIVE;
    }

    private boolean existsStandard(String idOrName) {
        if (StrUtil.isBlank(idOrName)) {
            return false;
        }
        GovStdField byId = stdFieldMapper.selectById(idOrName);
        if (byId != null && NOT_DELETE.equals(byId.getDeleteFlag())) {
            return true;
        }
        GovStdCode code = stdCodeMapper.selectById(idOrName);
        if (code != null && NOT_DELETE.equals(code.getDeleteFlag())) {
            return true;
        }
        Long byName = stdFieldMapper.selectCount(new QueryWrapper<GovStdField>().lambda()
                .eq(GovStdField::getDeleteFlag, NOT_DELETE)
                .eq(GovStdField::getFieldName, idOrName));
        return byName != null && byName > 0;
    }

    private String resolveLabel(KbTermRefs.EdgeDraft d) {
        if (KbTermRefs.KIND_METRIC.equals(d.toKind())) {
            List<GovMetric> list = metricMapper.selectList(new QueryWrapper<GovMetric>().lambda()
                    .eq(GovMetric::getDeleteFlag, NOT_DELETE)
                    .eq(GovMetric::getMetricCode, d.toId())
                    .last("LIMIT 1"));
            if (!list.isEmpty() && StrUtil.isNotBlank(list.get(0).getName())) {
                return list.get(0).getName();
            }
        }
        if (KbTermRefs.KIND_TERM.equals(d.toKind())) {
            GovKbEntry t = entryMapper.selectById(d.toId());
            if (t != null && StrUtil.isNotBlank(t.getTitle())) {
                return t.getTitle();
            }
        }
        if (KbTermRefs.KIND_STD.equals(d.toKind())) {
            GovStdField f = stdFieldMapper.selectById(d.toId());
            if (f != null && StrUtil.isNotBlank(f.getFieldName())) {
                return f.getFieldName();
            }
        }
        return StrUtil.blankToDefault(d.toLabel(), d.toId());
    }

    private Map<String, GovKbEntry> loadTermMap() {
        List<GovKbEntry> terms = entryMapper.selectList(new QueryWrapper<GovKbEntry>().lambda()
                .eq(GovKbEntry::getDeleteFlag, NOT_DELETE)
                .eq(GovKbEntry::getCat, CAT_TERM));
        Map<String, GovKbEntry> map = new HashMap<>();
        for (GovKbEntry t : terms) {
            map.put(t.getId(), t);
        }
        return map;
    }

    private static String nodeKey(String kind, String id) {
        if (KbTermRefs.KIND_TERM.equals(kind)) {
            return id;
        }
        return kind + ":" + id;
    }

    private Map<String, Object> nodeVo(String nid, Map<String, GovKbEntry> termById,
                                       List<GovKbTermEdge> allEdges) {
        Map<String, Object> n = new LinkedHashMap<>();
        if (termById.containsKey(nid)) {
            GovKbEntry t = termById.get(nid);
            n.put("id", nid);
            n.put("kind", KbTermRefs.KIND_TERM);
            n.put("label", t.getTitle());
            n.put("entryId", t.getId());
            return n;
        }
        int colon = nid.indexOf(':');
        if (colon > 0) {
            String kind = nid.substring(0, colon);
            String id = nid.substring(colon + 1);
            n.put("id", nid);
            n.put("kind", kind);
            n.put("label", labelFromEdges(allEdges, kind, id));
            n.put("refId", id);
            return n;
        }
        n.put("id", nid);
        n.put("kind", "unknown");
        n.put("label", nid);
        return n;
    }

    private static String labelFromEdges(List<GovKbTermEdge> edges, String kind, String id) {
        for (GovKbTermEdge e : edges) {
            if (kind.equals(e.getToKind()) && id.equals(e.getToId()) && StrUtil.isNotBlank(e.getToLabel())) {
                return e.getToLabel();
            }
        }
        return id;
    }

    private static Map<String, Object> edgeVo(GovKbTermEdge e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("fromId", e.getFromEntryId());
        m.put("toId", nodeKey(e.getToKind(), e.getToId()));
        m.put("edgeType", e.getEdgeType());
        m.put("toKind", e.getToKind());
        m.put("status", e.getStatus());
        m.put("toLabel", e.getToLabel());
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
