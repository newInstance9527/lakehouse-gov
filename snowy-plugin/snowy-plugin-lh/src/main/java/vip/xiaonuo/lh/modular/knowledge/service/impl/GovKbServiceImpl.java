package vip.xiaonuo.lh.modular.knowledge.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vip.xiaonuo.common.enums.CommonSortOrderEnum;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.ai.LhLiteLlmClient;
import vip.xiaonuo.lh.core.ai.LhMilvusClient;
import vip.xiaonuo.lh.modular.knowledge.entity.GovKbChunk;
import vip.xiaonuo.lh.modular.knowledge.entity.GovKbCiteStat;
import vip.xiaonuo.lh.modular.knowledge.entity.GovKbEntry;
import vip.xiaonuo.lh.modular.knowledge.mapper.GovKbChunkMapper;
import vip.xiaonuo.lh.modular.knowledge.mapper.GovKbCiteStatMapper;
import vip.xiaonuo.lh.modular.knowledge.mapper.GovKbEntryMapper;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbCiteAckParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbPageParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbSearchParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbUpsertParam;
import vip.xiaonuo.lh.modular.knowledge.result.GovKbEntryVo;
import vip.xiaonuo.lh.modular.knowledge.service.GovKbService;
import vip.xiaonuo.lh.modular.knowledge.support.KbChunker;
import vip.xiaonuo.lh.modular.knowledge.support.KbDocumentParser;
import vip.xiaonuo.lh.modular.knowledge.support.KbTermGraphHelper;
import vip.xiaonuo.lh.modular.knowledge.support.KbTermRefs;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

/**
 * 知识库：分片索引 + Milvus 向量（未启用则关键词）；纪要记忆质量与老化
 */
@Slf4j
@Service
public class GovKbServiceImpl implements GovKbService {

    private static final String WS_DEFAULT = "default";
    /** 公用知识库在 ws 列的哨兵值（Milvus 过滤用） */
    private static final String WS_PLATFORM = "_platform";
    private static final String SCOPE_WORKSPACE = "workspace";
    private static final String SCOPE_PLATFORM = "platform";
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String LIFECYCLE_ACTIVE = "active";
    private static final String LIFECYCLE_ARCHIVE = "archive";
    private static final String LIFECYCLE_DELETED = "deleted";
    private static final String CAT_MINUTES = "minutes";
    /** 低分（≤3）无访问天数 → 归档 */
    private static final int LOW_SCORE_IDLE_DAYS = 90;
    /** 普通分（≤6）无访问天数 → 归档 */
    private static final int MID_SCORE_IDLE_DAYS = 180;
    /** 归档后再过此天数 soft-delete */
    private static final int ARCHIVE_PURGE_DAYS = 180;

    @Resource
    private GovKbEntryMapper entryMapper;
    @Resource
    private GovKbChunkMapper chunkMapper;
    @Resource
    private GovKbCiteStatMapper citeStatMapper;
    @Resource
    private LhMilvusClient milvusClient;
    @Resource
    private LhLiteLlmClient liteLlmClient;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private KbTermGraphHelper termGraphHelper;
    @Resource
    private GovMetricMapper metricMapper;

    @Override
    public Map<String, Object> overview(String ws) {
        return overview(ws, null);
    }

    @Override
    public Map<String, Object> overview(String ws, String scope) {
        // 知识库全局：列表/KPI 不再按 workspace|platform 分库
        List<GovKbEntry> all = entryMapper.selectList(new QueryWrapper<GovKbEntry>().lambda()
                .eq(GovKbEntry::getDeleteFlag, NOT_DELETE));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("termCount", countCat(all, "term"));
        out.put("dictCount", countCat(all, "dict"));
        out.put("practiceCount", countCat(all, "practice"));
        out.put("faqCount", countCat(all, "faq"));
        out.put("manualCount", countCat(all, "manual"));
        out.put("minutesCount", countCat(all, CAT_MINUTES));
        long archived = all.stream().filter(e -> LIFECYCLE_ARCHIVE.equalsIgnoreCase(e.getLifecycleStatus())).count();
        out.put("minutesArchived", archived);
        out.put("citeCnt", all.stream().mapToInt(e -> e.getCiteCnt() == null ? 0 : e.getCiteCnt()).sum());
        out.put("citeTotal", all.stream().mapToInt(e -> e.getCiteCnt() == null ? 0 : e.getCiteCnt()).sum());
        out.put("ws", "*");
        out.put("scope", "global");
        out.put("canWritePlatform", true);
        Map<String, Object> probe = milvusClient.probe();
        out.put("milvusEnabled", probe.get("enabled"));
        out.put("milvusReachable", probe.get("reachable"));
        out.put("retrievalMode", probe.get("mode"));
        out.put("milvusCollection", probe.get("collection"));
        try {
            Map<String, Object> gloss = termGraphHelper.glossaryStats();
            out.put("glossary", gloss);
            out.put("metricTermCoveragePct", gloss.get("metricTermCoveragePct"));
            out.put("brokenEdgeRatePct", gloss.get("brokenEdgeRatePct"));
            out.put("termLinkRatePct", gloss.get("termLinkRatePct"));
        } catch (Exception e) {
            log.debug("glossary stats soft-fail: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public Map<String, Object> vectorProbe() {
        Map<String, Object> out = new LinkedHashMap<>(milvusClient.probe());
        out.put("embedAvailable", liteLlmClient.available());
        LhProperties.Ai ai = lhProperties.getAi();
        out.put("embedModel", ai == null ? "" : StrUtil.nullToEmpty(ai.getDefaultEmbedModel()));
        out.put("vectorWeight", ai == null ? 0.7 : ai.getVectorWeight());
        // 真混合还需 embed；否则即便 Milvus 可达也只能关键词
        boolean hybridReady = Boolean.TRUE.equals(out.get("reachable")) && liteLlmClient.available();
        if (Boolean.TRUE.equals(out.get("enabled")) && hybridReady) {
            out.put("mode", "hybrid");
            out.put("message", "Milvus + embed 可用；search 走混合检索");
        } else if (Boolean.TRUE.equals(out.get("enabled")) && Boolean.TRUE.equals(out.get("reachable"))) {
            out.put("mode", "vector-only-no-embed");
            out.put("message", "Milvus 可达但 LiteLLM embed 不可用；search 仍关键词");
        }
        return out;
    }

    @Override
    public Page<GovKbEntryVo> page(GovKbPageParam param) {
        QueryWrapper<GovKbEntry> qw = new QueryWrapper<GovKbEntry>().checkSqlInjection();
        qw.lambda().eq(GovKbEntry::getDeleteFlag, NOT_DELETE)
                .and(w -> w.isNull(GovKbEntry::getLifecycleStatus)
                        .or().ne(GovKbEntry::getLifecycleStatus, LIFECYCLE_DELETED));
        // 全局列表：忽略 ws / scope（历史 platform/workspace 列仅作痕迹）
        if (param != null && StrUtil.isNotBlank(param.getCat()) && !"all".equalsIgnoreCase(param.getCat())) {
            qw.lambda().eq(GovKbEntry::getCat, param.getCat().trim().toLowerCase(Locale.ROOT));
        }
        if (param != null && StrUtil.isNotBlank(param.getStatus()) && !"all".equalsIgnoreCase(param.getStatus())) {
            qw.lambda().eq(GovKbEntry::getStatus, param.getStatus().trim().toLowerCase(Locale.ROOT));
        }
        if (param != null && StrUtil.isNotBlank(param.getQ())) {
            String kw = param.getQ().trim();
            qw.lambda().and(w -> w.like(GovKbEntry::getTitle, kw).or().like(GovKbEntry::getBody, kw));
        }
        if (param != null && StrUtil.isNotBlank(param.getSortField())) {
            String order = StrUtil.blankToDefault(param.getSortOrder(), CommonSortOrderEnum.DESC.getValue());
            CommonSortOrderEnum.validate(order);
            qw.orderBy(true, order.equalsIgnoreCase(CommonSortOrderEnum.ASC.getValue()),
                    StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByDesc(GovKbEntry::getUpdateTime).orderByDesc(GovKbEntry::getCreateTime);
        }
        Page<GovKbEntry> raw = entryMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<GovKbEntryVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream().map(e -> toVo(e, false)).toList());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovKbEntryVo create(GovKbUpsertParam param) {
        if (StrUtil.isBlank(param.getTitle())) {
            throw new CommonException("title 不能为空");
        }
        if (StrUtil.isBlank(param.getBody())) {
            throw new CommonException("body 不能为空");
        }
        String sc = SCOPE_WORKSPACE;
        // 全局知识库：不再区分公用/空间写权限
        GovKbEntry row = new GovKbEntry();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setRevision(1);
        row.setStatus("indexing");
        row.setScope(sc);
        row.setWs(StrUtil.blankToDefault(param.getWs(), WS_DEFAULT));
        row.setRemark(param.getRemark());
        row.setCat(normalizeCat(param.getCat()));
        row.setTitle(param.getTitle().trim());
        row.setBody(param.getBody());
        row.setSource(StrUtil.blankToDefault(param.getSource(), "manual"));
        row.setFileName(param.getFileName());
        row.setStrategy(StrUtil.blankToDefault(param.getStrategy(), "fixed"));
        row.setChunkSize(param.getChunkSize() == null ? 500 : param.getChunkSize());
        row.setOverlap(param.getOverlap() == null ? 50 : param.getOverlap());
        row.setSeparator(param.getSeparator());
        row.setEmbedModelId(param.getEmbedModelId());
        row.setRefsJson(resolveRefsJson(param));
        row.setCiteCnt(0);
        row.setLifecycleStatus(LIFECYCLE_ACTIVE);
        row.setTagsJson(param.getTagsJson());
        if (param.getQualityScore() != null) {
            row.setQualityScore(clampScore(param.getQualityScore()));
            row.setScoreSource("human");
            row.setScoreReason(StrUtil.blankToDefault(param.getScoreReason(), "人工评分"));
        }
        row.setDeleteFlag(NOT_DELETE);
        entryMapper.insert(row);
        indexEntry(row);
        try {
            termGraphHelper.syncEdgesForEntry(row);
        } catch (Exception e) {
            log.debug("term edge sync soft-fail: {}", e.getMessage());
        }
        if (CAT_MINUTES.equals(row.getCat()) && !"human".equalsIgnoreCase(row.getScoreSource())) {
            try {
                scoreMinutesEntry(row.getId());
            } catch (Exception e) {
                log.debug("minutes score soft-fail: {}", e.getMessage());
            }
        }
        return toVo(requireEntry(row.getId()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovKbEntryVo upload(MultipartFile file, GovKbUpsertParam meta, String entryId) {
        if (file == null || file.isEmpty()) {
            throw new CommonException("请上传文档文件");
        }
        KbDocumentParser.ParseResult parsed;
        try {
            parsed = KbDocumentParser.parse(
                    file.getOriginalFilename(),
                    file.getSize(),
                    file.getInputStream());
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("读取上传文件失败：{}",
                    StrUtil.maxLength(StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()), 120));
        }

        GovKbUpsertParam param = meta == null ? new GovKbUpsertParam() : meta;
        param.setBody(parsed.text());
        param.setSource("upload");
        param.setFileName(parsed.fileName());
        if (StrUtil.isBlank(param.getTitle())) {
            String base = parsed.fileName();
            int dot = base.lastIndexOf('.');
            param.setTitle(dot > 0 ? base.substring(0, dot) : base);
        }

        if (StrUtil.isNotBlank(entryId)) {
            return update(entryId.trim(), param);
        }
        return create(param);
    }

    @Override
    public GovKbEntryVo detail(String id) {
        return toVo(requireEntry(id), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovKbEntryVo update(String id, GovKbUpsertParam param) {
        GovKbEntry row = requireEntry(id);
        if (StrUtil.isNotBlank(param.getScope())) {
            // 兼容旧客户端：统一落到 workspace，不再切 platform
            row.setScope(SCOPE_WORKSPACE);
            if (StrUtil.isNotBlank(param.getWs()) && !WS_PLATFORM.equals(param.getWs().trim())) {
                row.setWs(param.getWs().trim());
            } else if (WS_PLATFORM.equals(row.getWs())) {
                row.setWs(WS_DEFAULT);
            }
        }
        if (StrUtil.isNotBlank(param.getTitle())) {
            row.setTitle(param.getTitle().trim());
        }
        if (StrUtil.isNotBlank(param.getCat())) {
            row.setCat(normalizeCat(param.getCat()));
        }
        boolean reindex = false;
        if (param.getBody() != null) {
            row.setBody(param.getBody());
            reindex = true;
        }
        if (StrUtil.isNotBlank(param.getSource())) {
            row.setSource(param.getSource());
        }
        if (param.getFileName() != null) {
            row.setFileName(param.getFileName());
        }
        if (StrUtil.isNotBlank(param.getStrategy())) {
            row.setStrategy(param.getStrategy());
            reindex = true;
        }
        if (param.getChunkSize() != null) {
            row.setChunkSize(param.getChunkSize());
            reindex = true;
        }
        if (param.getOverlap() != null) {
            row.setOverlap(param.getOverlap());
            reindex = true;
        }
        if (param.getSeparator() != null) {
            row.setSeparator(param.getSeparator());
        }
        if (param.getEmbedModelId() != null) {
            row.setEmbedModelId(param.getEmbedModelId());
        }
        String refs = resolveRefsJson(param);
        if (refs != null && (param.getRefs() != null || param.getRefsJson() != null)) {
            row.setRefsJson(refs);
        }
        if (param.getRemark() != null) {
            row.setRemark(param.getRemark());
        }
        if (param.getTagsJson() != null) {
            row.setTagsJson(param.getTagsJson());
        }
        if (param.getQualityScore() != null) {
            row.setQualityScore(clampScore(param.getQualityScore()));
            row.setScoreSource("human");
            row.setScoreReason(StrUtil.blankToDefault(param.getScoreReason(), "人工评分"));
        }
        if (StrUtil.isNotBlank(param.getLifecycleStatus())) {
            String lc = param.getLifecycleStatus().trim().toLowerCase(Locale.ROOT);
            if (LIFECYCLE_ACTIVE.equals(lc) || LIFECYCLE_ARCHIVE.equals(lc) || LIFECYCLE_DELETED.equals(lc)) {
                row.setLifecycleStatus(lc);
            }
        }
        if (SCOPE_WORKSPACE.equals(effectiveScope(row)) && StrUtil.isNotBlank(param.getWs())
                && !WS_PLATFORM.equals(param.getWs().trim())) {
            row.setWs(param.getWs().trim());
        }
        row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
        if (reindex) {
            row.setStatus("indexing");
            entryMapper.updateById(row);
            indexEntry(row);
        } else {
            entryMapper.updateById(row);
        }
        try {
            termGraphHelper.syncEdgesForEntry(row);
        } catch (Exception e) {
            log.debug("term edge sync soft-fail: {}", e.getMessage());
        }
        return toVo(row, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        GovKbEntry row = requireEntry(id);
        row.setDeleteFlag("DELETED");
        entryMapper.updateById(row);
        chunkMapper.delete(new QueryWrapper<GovKbChunk>().lambda().eq(GovKbChunk::getEntryId, id));
        try {
            milvusClient.deleteByEntryId(id);
        } catch (Exception ignored) {
            // soft-fail
        }
        try {
            termGraphHelper.clearEdges(id);
        } catch (Exception e) {
            log.debug("term edge clear soft-fail: {}", e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rebuild(String entryId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(entryId)) {
            GovKbEntry row = requireEntry(entryId);
            row.setStatus("indexing");
            entryMapper.updateById(row);
            int n = indexEntry(row);
            out.put("entryId", entryId);
            out.put("chunkCount", n);
            out.put("status", row.getStatus());
            return out;
        }
        List<GovKbEntry> all = entryMapper.selectList(new QueryWrapper<GovKbEntry>().lambda()
                .eq(GovKbEntry::getDeleteFlag, NOT_DELETE));
        int totalChunks = 0;
        for (GovKbEntry e : all) {
            e.setStatus("indexing");
            entryMapper.updateById(e);
            totalChunks += indexEntry(e);
        }
        out.put("entryCount", all.size());
        out.put("chunkCount", totalChunks);
        return out;
    }

    @Override
    public List<Map<String, Object>> search(GovKbSearchParam param) {
        if (param == null || StrUtil.isBlank(param.getQuery())) {
            return List.of();
        }
        String q = param.getQuery().trim();
        int topK = param.getTopK() == null ? 5 : Math.max(1, Math.min(param.getTopK(), 50));

        QueryWrapper<GovKbEntry> entryQw = new QueryWrapper<>();
        entryQw.lambda()
                .eq(GovKbEntry::getDeleteFlag, NOT_DELETE)
                .eq(GovKbEntry::getStatus, "ready")
                .and(w -> w.isNull(GovKbEntry::getLifecycleStatus)
                        .or().eq(GovKbEntry::getLifecycleStatus, LIFECYCLE_ACTIVE));
        // 传 ws 时：本空间 workspace 条目 +（可选）platform；不传则全局（兼容旧调用）
        if (StrUtil.isNotBlank(param.getWs())) {
            String ws = param.getWs().trim();
            boolean includePlatform = !Boolean.FALSE.equals(param.getIncludePlatform());
            entryQw.lambda().and(w -> {
                w.eq(GovKbEntry::getWs, ws);
                if (includePlatform) {
                    w.or().eq(GovKbEntry::getScope, SCOPE_PLATFORM)
                            .or().eq(GovKbEntry::getWs, WS_PLATFORM);
                }
            });
        }
        if (param.getCats() != null && !param.getCats().isEmpty()) {
            entryQw.lambda().in(GovKbEntry::getCat, param.getCats());
        }
        List<GovKbEntry> entries = entryMapper.selectList(entryQw);
        if (entries.isEmpty()) {
            return List.of();
        }
        // 元数据过滤（filters.metricCode / assetId / domain …）先于打分
        entries = applySearchFilters(entries, param.getFilters());
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<String, GovKbEntry> entryMap = entries.stream()
                .collect(Collectors.toMap(GovKbEntry::getId, e -> e, (a, b) -> a));
        Set<String> entryIds = entryMap.keySet();

        List<GovKbChunk> chunks = chunkMapper.selectList(new QueryWrapper<GovKbChunk>().lambda()
                .in(GovKbChunk::getEntryId, entryIds)
                .like(GovKbChunk::getTextContent, q)
                .last("LIMIT " + (topK * 5)));
        // 标题命中补强
        Set<String> titleHitIds = entries.stream()
                .filter(e -> StrUtil.containsIgnoreCase(e.getTitle(), q)
                        || StrUtil.containsIgnoreCase(StrUtil.nullToEmpty(e.getBody()), q))
                .map(GovKbEntry::getId)
                .collect(Collectors.toCollection(HashSet::new));
        if (chunks.isEmpty() && !titleHitIds.isEmpty()) {
            chunks = chunkMapper.selectList(new QueryWrapper<GovKbChunk>().lambda()
                    .in(GovKbChunk::getEntryId, titleHitIds)
                    .orderByAsc(GovKbChunk::getOrdinal)
                    .last("LIMIT " + topK));
        }

        Map<String, Map<String, Object>> byChunk = new LinkedHashMap<>();
        for (GovKbChunk c : chunks) {
            GovKbEntry e = entryMap.get(c.getEntryId());
            if (e == null) {
                continue;
            }
            double kw = scoreKeyword(q, c.getTextContent(), e.getTitle());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entryId", e.getId());
            m.put("chunkId", c.getId());
            m.put("title", e.getTitle());
            m.put("cat", e.getCat());
            m.put("ws", e.getWs());
            m.put("scope", effectiveScope(e));
            m.put("text", c.getTextContent());
            m.put("score", kw);
            m.put("kwScore", kw);
            m.put("vecScore", 0.0);
            m.put("refs", parseRefs(e.getRefsJson()));
            m.put("ordinal", c.getOrdinal());
            byChunk.put(c.getId(), m);
        }

        // 混合：Milvus 可用且 query embed 成功时合并向量分（全局，不按 ws 过滤）
        double vectorWeight = 0.7;
        boolean usedVector = false;
        List<String> milvusExtraWs = List.of();
        String milvusWs = null;
        try {
            LhProperties.Ai ai = lhProperties.getAi();
            if (ai != null) {
                vectorWeight = Math.max(0, Math.min(1, ai.getVectorWeight()));
            }
            if (milvusClient.available() && liteLlmClient.available()) {
                List<float[]> qVecs = liteLlmClient.embed(
                        ai == null ? null : ai.getDefaultEmbedModel(), List.of(q));
                if (qVecs != null && !qVecs.isEmpty() && qVecs.get(0) != null) {
                    List<Map<String, Object>> vecHits = milvusClient.search(
                            milvusWs, milvusExtraWs, qVecs.get(0), topK * 3, param.getCats());
                    if (!vecHits.isEmpty()) {
                        usedVector = true;
                    }
                    for (Map<String, Object> vh : vecHits) {
                        String chunkId = vh.get("chunkId") == null ? null : String.valueOf(vh.get("chunkId"));
                        String entryId = vh.get("entryId") == null ? null : String.valueOf(vh.get("entryId"));
                        if (StrUtil.isBlank(chunkId) || !entryIds.contains(entryId)) {
                            continue;
                        }
                        double vec = vh.get("score") instanceof Number n ? n.doubleValue() : 0;
                        Map<String, Object> existing = byChunk.get(chunkId);
                        if (existing != null) {
                            existing.put("vecScore", vec);
                        } else {
                            GovKbChunk c = chunkMapper.selectById(chunkId);
                            GovKbEntry e = entryMap.get(entryId);
                            if (c == null || e == null) {
                                continue;
                            }
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("entryId", e.getId());
                            m.put("chunkId", c.getId());
                            m.put("title", e.getTitle());
                            m.put("cat", e.getCat());
                            m.put("ws", e.getWs());
                            m.put("scope", effectiveScope(e));
                            m.put("text", c.getTextContent());
                            m.put("kwScore", 0.0);
                            m.put("vecScore", vec);
                            m.put("refs", parseRefs(e.getRefsJson()));
                            m.put("ordinal", c.getOrdinal());
                            byChunk.put(chunkId, m);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // soft-fail → 仅关键词
            usedVector = false;
        }

        String retrievalMode = usedVector ? "hybrid" : "keyword";
        double kwWeight = 1.0 - vectorWeight;
        double maxKw = byChunk.values().stream()
                .mapToDouble(m -> ((Number) m.getOrDefault("kwScore", 0)).doubleValue())
                .max().orElse(0);
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> m : byChunk.values()) {
            double kw = ((Number) m.getOrDefault("kwScore", 0)).doubleValue();
            double vec = ((Number) m.getOrDefault("vecScore", 0)).doubleValue();
            double kwNorm = maxKw > 0 ? kw / maxKw : 0;
            // COSINE 相似度通常已在 [-1,1]，截断到 [0,1]
            double vecNorm = Math.max(0, Math.min(1, vec));
            boolean hasVec = vec > 0;
            double score = hasVec
                    ? (vectorWeight * vecNorm + kwWeight * kwNorm)
                    : kwNorm;
            // 质量分与近访问加成（纪要记忆 / 通用条目均可）
            GovKbEntry entry = entryMap.get(String.valueOf(m.get("entryId")));
            double qBoost = qualityBoost(entry);
            double accessBoost = accessRecencyBoost(entry);
            score = Math.min(1.5, score * qBoost + accessBoost);
            m.put("score", score);
            m.put("qualityScore", entry == null || entry.getQualityScore() == null
                    ? null : entry.getQualityScore().doubleValue());
            m.put("retrievalMode", retrievalMode);
            scored.add(m);
        }
        scored.sort(Comparator.comparingDouble((Map<String, Object> m) -> (Double) m.get("score")).reversed());
        List<Map<String, Object>> top = scored.size() > topK ? scored.subList(0, topK) : scored;
        // 检索命中回写访问时间（异步感：同事务轻量更新）
        List<String> touched = top.stream()
                .map(m -> String.valueOf(m.get("entryId")))
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        touchLastAccess(touched);
        return top;
    }

    @Override
    public Map<String, Object> stats(String ws) {
        List<GovKbEntry> entries = entryMapper.selectList(new QueryWrapper<GovKbEntry>().lambda()
                .eq(GovKbEntry::getDeleteFlag, NOT_DELETE)
                .orderByDesc(GovKbEntry::getCiteCnt)
                .last("LIMIT 20"));
        List<Map<String, Object>> hot = entries.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entryId", e.getId());
            m.put("title", e.getTitle());
            m.put("cat", e.getCat());
            m.put("scope", "global");
            m.put("citeCnt", e.getCiteCnt() == null ? 0 : e.getCiteCnt());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hot", hot);
        out.put("ws", "*");
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ackCitation(GovKbCiteAckParam param) {
        Set<String> entryIds = new HashSet<>();
        if (param != null && param.getEntryIds() != null) {
            entryIds.addAll(param.getEntryIds());
        }
        if (param != null && param.getChunkIds() != null && !param.getChunkIds().isEmpty()) {
            List<GovKbChunk> chunks = chunkMapper.selectBatchIds(param.getChunkIds());
            for (GovKbChunk c : chunks) {
                entryIds.add(c.getEntryId());
            }
        }
        Date today = truncateDay(new Date());
        int updated = 0;
        for (String entryId : entryIds) {
            if (StrUtil.isBlank(entryId)) {
                continue;
            }
            GovKbEntry e = entryMapper.selectById(entryId);
            if (e == null || !NOT_DELETE.equals(e.getDeleteFlag())) {
                continue;
            }
            e.setCiteCnt((e.getCiteCnt() == null ? 0 : e.getCiteCnt()) + 1);
            e.setLastAccessAt(new Date());
            entryMapper.updateById(e);

            GovKbCiteStat stat = citeStatMapper.selectOne(new QueryWrapper<GovKbCiteStat>().lambda()
                    .eq(GovKbCiteStat::getEntryId, entryId)
                    .eq(GovKbCiteStat::getDay, today)
                    .last("LIMIT 1"));
            if (stat == null) {
                stat = new GovKbCiteStat();
                stat.setId(IdUtil.getSnowflakeNextIdStr());
                stat.setEntryId(entryId);
                stat.setDay(today);
                stat.setCiteCnt(1);
                citeStatMapper.insert(stat);
            } else {
                stat.setCiteCnt((stat.getCiteCnt() == null ? 0 : stat.getCiteCnt()) + 1);
                citeStatMapper.updateById(stat);
            }
            updated++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("acked", updated);
        return out;
    }

    @Override
    public void touchLastAccess(List<String> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) {
            return;
        }
        Date now = new Date();
        for (String id : entryIds) {
            if (StrUtil.isBlank(id)) {
                continue;
            }
            try {
                GovKbEntry e = entryMapper.selectById(id);
                if (e == null || !NOT_DELETE.equals(e.getDeleteFlag())) {
                    continue;
                }
                e.setLastAccessAt(now);
                entryMapper.updateById(e);
            } catch (Exception ignored) {
                // soft
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void scoreMinutesEntry(String entryId) {
        if (StrUtil.isBlank(entryId)) {
            return;
        }
        GovKbEntry row = entryMapper.selectById(entryId);
        if (row == null || !NOT_DELETE.equals(row.getDeleteFlag())) {
            return;
        }
        if ("human".equalsIgnoreCase(row.getScoreSource()) && row.getQualityScore() != null) {
            return;
        }
        String text = (StrUtil.nullToEmpty(row.getTitle()) + "\n" + StrUtil.nullToEmpty(row.getBody())).toLowerCase(Locale.ROOT);
        double score = 4.0;
        List<String> reasons = new ArrayList<>();
        if (text.contains("结论") || text.contains("决策") || text.contains("里程碑")) {
            score += 2.0;
            reasons.add("含决策/结论/里程碑");
        }
        if (text.contains("风险")) {
            score += 1.0;
            reasons.add("含风险点");
        }
        if (text.contains("待办") || text.contains("责任人") || text.contains("ddl") || text.contains("截止")) {
            score += 1.5;
            reasons.add("含待办/责任人/时间点");
        }
        if (text.length() < 80) {
            score -= 1.5;
            reasons.add("内容过短");
        }
        if (text.contains("闲聊") || text.contains("哈哈哈") || text.contains("测试一下")) {
            score -= 2.0;
            reasons.add("疑似闲聊");
        }
        if (text.contains("重复") || text.contains("同上")) {
            score -= 1.0;
            reasons.add("疑似重复");
        }
        score = Math.max(0, Math.min(10, score));
        row.setQualityScore(BigDecimal.valueOf(score).setScale(1, RoundingMode.HALF_UP));
        row.setScoreSource("agent");
        row.setScoreReason(reasons.isEmpty() ? "启发式默认分" : String.join("；", reasons));
        if (StrUtil.isBlank(row.getLifecycleStatus())) {
            row.setLifecycleStatus(LIFECYCLE_ACTIVE);
        }
        entryMapper.updateById(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> memoryPatrol() {
        List<GovKbEntry> rows = entryMapper.selectList(new QueryWrapper<GovKbEntry>().lambda()
                .eq(GovKbEntry::getDeleteFlag, NOT_DELETE)
                .eq(GovKbEntry::getCat, CAT_MINUTES));
        int archived = 0;
        int purged = 0;
        int protectedCnt = 0;
        Date now = new Date();
        for (GovKbEntry e : rows) {
            if (e == null) {
                continue;
            }
            String lc = StrUtil.blankToDefault(e.getLifecycleStatus(), LIFECYCLE_ACTIVE);
            if (LIFECYCLE_DELETED.equalsIgnoreCase(lc)) {
                continue;
            }
            if (isMemoryProtected(e)) {
                protectedCnt++;
                continue;
            }
            if (LIFECYCLE_ARCHIVE.equalsIgnoreCase(lc)) {
                Date base = e.getUpdateTime() != null ? e.getUpdateTime() : e.getCreateTime();
                if (base != null && daysBetween(base, now) >= ARCHIVE_PURGE_DAYS) {
                    e.setLifecycleStatus(LIFECYCLE_DELETED);
                    e.setDeleteFlag("DELETED");
                    e.setRevision(e.getRevision() == null ? 1 : e.getRevision() + 1);
                    entryMapper.updateById(e);
                    purged++;
                }
                continue;
            }
            double q = e.getQualityScore() == null ? 5.0 : e.getQualityScore().doubleValue();
            Date access = e.getLastAccessAt() != null ? e.getLastAccessAt()
                    : (e.getUpdateTime() != null ? e.getUpdateTime() : e.getCreateTime());
            long idle = access == null ? Long.MAX_VALUE : daysBetween(access, now);
            boolean shouldArchive = (q <= 3 && idle >= LOW_SCORE_IDLE_DAYS)
                    || (q <= 6 && idle >= MID_SCORE_IDLE_DAYS);
            if (!shouldArchive) {
                continue;
            }
            e.setLifecycleStatus(LIFECYCLE_ARCHIVE);
            e.setRevision(e.getRevision() == null ? 1 : e.getRevision() + 1);
            entryMapper.updateById(e);
            try {
                milvusClient.deleteByEntryId(e.getId());
            } catch (Exception ignored) {
                // soft
            }
            archived++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", rows.size());
        out.put("archived", archived);
        out.put("purged", purged);
        out.put("protected", protectedCnt);
        return out;
    }

    private static boolean isMemoryProtected(GovKbEntry e) {
        if (e.getQualityScore() != null && e.getQualityScore().doubleValue() >= 7.0) {
            return true;
        }
        String tags = StrUtil.nullToEmpty(e.getTagsJson()).toLowerCase(Locale.ROOT);
        return tags.contains("里程碑") || tags.contains("milestone")
                || tags.contains("决策") || tags.contains("decision")
                || tags.contains("合同");
    }

    private static long daysBetween(Date from, Date to) {
        if (from == null || to == null) {
            return 0;
        }
        long ms = Math.max(0, to.getTime() - from.getTime());
        return ms / (24L * 60L * 60L * 1000L);
    }

    private static double qualityBoost(GovKbEntry e) {
        if (e == null || e.getQualityScore() == null) {
            return 1.0;
        }
        double q = e.getQualityScore().doubleValue();
        // 0→0.7 … 5→1.0 … 10→1.3
        return 0.7 + (q / 10.0) * 0.6;
    }

    private static double accessRecencyBoost(GovKbEntry e) {
        if (e == null || e.getLastAccessAt() == null) {
            return 0;
        }
        long days = daysBetween(e.getLastAccessAt(), new Date());
        if (days <= 7) {
            return 0.08;
        }
        if (days <= 30) {
            return 0.04;
        }
        return 0;
    }

    private static BigDecimal clampScore(BigDecimal s) {
        if (s == null) {
            return null;
        }
        double v = Math.max(0, Math.min(10, s.doubleValue()));
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
    }

    private int indexEntry(GovKbEntry row) {
        chunkMapper.delete(new QueryWrapper<GovKbChunk>().lambda().eq(GovKbChunk::getEntryId, row.getId()));
        try {
            milvusClient.deleteByEntryId(row.getId());
        } catch (Exception ignored) {
            // soft-fail
        }
        if (StrUtil.isBlank(row.getBody())) {
            markIndex(row, "failed", "none", "body 为空，无法分片");
            return 0;
        }
        List<String> parts;
        try {
            parts = KbChunker.split(
                    row.getStrategy(),
                    row.getBody(),
                    row.getChunkSize() == null ? 500 : row.getChunkSize(),
                    row.getOverlap() == null ? 50 : row.getOverlap(),
                    row.getSeparator());
        } catch (Exception ex) {
            markIndex(row, "failed", "none", "分片失败: " + StrUtil.maxLength(ex.getMessage(), 200));
            return 0;
        }
        if (parts.isEmpty()) {
            markIndex(row, "failed", "none", "分片结果为空");
            return 0;
        }
        Date now = new Date();
        int ordinal = 0;
        List<GovKbChunk> inserted = new ArrayList<>();
        try {
            for (String part : parts) {
                GovKbChunk c = new GovKbChunk();
                c.setId(IdUtil.getSnowflakeNextIdStr());
                c.setEntryId(row.getId());
                c.setOrdinal(ordinal++);
                c.setTextContent(part);
                c.setTokenEst(KbChunker.estimateTokens(part));
                c.setCreateTime(now);
                chunkMapper.insert(c);
                inserted.add(c);
            }
        } catch (Exception ex) {
            markIndex(row, "failed", "none", "写入分片失败: " + StrUtil.maxLength(ex.getMessage(), 200));
            return 0;
        }
        // 向量化：Milvus + embed 成功则 upsert；否则关键词可用，status=ready + indexMode=keyword
        String indexMode = "keyword";
        String indexError = null;
        int upserted = 0;
        try {
            if (!milvusClient.available()) {
                indexError = "Milvus 未启用或不可达；仅关键词检索";
            } else if (!liteLlmClient.available()) {
                indexError = "LiteLLM/embed 不可用；仅关键词检索";
            } else if (!inserted.isEmpty()) {
                List<String> texts = inserted.stream().map(GovKbChunk::getTextContent).toList();
                String embedModel = StrUtil.blankToDefault(row.getEmbedModelId(),
                        lhProperties.getAi() == null ? null : lhProperties.getAi().getDefaultEmbedModel());
                List<float[]> vectors = liteLlmClient.embed(embedModel, texts);
                if (vectors == null || vectors.size() != inserted.size()) {
                    indexError = "embed 返回条数不匹配；仅关键词检索";
                } else {
                    int dim = vectors.get(0) == null ? 0 : vectors.get(0).length;
                    if (dim <= 0 || !milvusClient.ensureCollection(dim)) {
                        indexError = "Milvus ensureCollection 失败；仅关键词检索";
                    } else {
                        for (int i = 0; i < inserted.size(); i++) {
                            float[] vec = vectors.get(i);
                            if (vec == null || vec.length == 0) {
                                continue;
                            }
                            GovKbChunk c = inserted.get(i);
                            if (milvusClient.upsert(c.getId(), row.getId(), row.getWs(), row.getCat(), vec)) {
                                upserted++;
                            }
                        }
                        if (upserted > 0) {
                            indexMode = "hybrid";
                            indexError = null;
                        } else {
                            indexError = "向量 upsert 全部失败；仅关键词检索";
                        }
                    }
                }
            }
        } catch (Exception ex) {
            indexMode = "keyword";
            indexError = "向量化异常: " + StrUtil.maxLength(ex.getMessage(), 180);
        }
        markIndex(row, "ready", indexMode, indexError);
        return parts.size();
    }

    private void markIndex(GovKbEntry row, String status, String indexMode, String indexError) {
        row.setStatus(status);
        row.setRemark(mergeIndexRemark(row.getRemark(), indexMode, indexError));
        entryMapper.updateById(row);
    }

    /** 保留业务 remark，覆盖 indexMode=/indexError= 标记 */
    static String mergeIndexRemark(String remark, String indexMode, String indexError) {
        String base = StrUtil.nullToEmpty(remark)
                .replaceAll("(?i)\\s*indexMode=[^|;\\s]+", "")
                .replaceAll("(?i)\\s*indexError=[^|]*", "")
                .replaceAll("\\|\\|+", "|")
                .trim();
        if (base.startsWith("|")) {
            base = base.substring(1).trim();
        }
        if (base.endsWith("|")) {
            base = base.substring(0, base.length() - 1).trim();
        }
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(base)) {
            sb.append(base);
        }
        if (StrUtil.isNotBlank(indexMode)) {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append("indexMode=").append(indexMode);
        }
        if (StrUtil.isNotBlank(indexError)) {
            if (!sb.isEmpty()) {
                sb.append(" | ");
            }
            sb.append("indexError=").append(indexError.replace('|', '/'));
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static List<GovKbEntry> applySearchFilters(List<GovKbEntry> entries, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return entries;
        }
        List<GovKbEntry> out = new ArrayList<>();
        for (GovKbEntry e : entries) {
            if (matchFilters(e.getRefsJson(), filters)) {
                out.add(e);
            }
        }
        return out;
    }

    /** filters 键：metricCode / metric / assetId / asset / domain / label — 任一非空则须在 refs 中命中 */
    private static boolean matchFilters(String refsJson, Map<String, Object> filters) {
        String refs = StrUtil.nullToEmpty(refsJson).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> fe : filters.entrySet()) {
            if (fe.getValue() == null) {
                continue;
            }
            String key = StrUtil.nullToEmpty(fe.getKey()).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            String val = String.valueOf(fe.getValue()).trim();
            if (StrUtil.isBlank(val)) {
                continue;
            }
            String v = val.toLowerCase(Locale.ROOT);
            // 值命中即可；同时允许 key 出现在 JSON 旁（宽松）
            if (!(refs.contains(v) || refs.contains("\"" + key + "\""))) {
                return false;
            }
        }
        return true;
    }

    private GovKbEntry requireEntry(String id) {
        GovKbEntry row = entryMapper.selectById(id);
        if (row == null || !NOT_DELETE.equals(row.getDeleteFlag())) {
            throw new CommonException("知识条目不存在: {}", id);
        }
        return row;
    }

    private GovKbEntryVo toVo(GovKbEntry e, boolean withChunks) {
        GovKbEntryVo vo = new GovKbEntryVo();
        vo.setId(e.getId());
        vo.setTitle(e.getTitle());
        vo.setCat(e.getCat());
        vo.setBody(e.getBody());
        vo.setDesc(StrUtil.maxLength(StrUtil.nullToEmpty(e.getBody()), 120));
        vo.setSource(e.getSource());
        vo.setFileName(e.getFileName());
        vo.setStrategy(e.getStrategy());
        vo.setChunkSize(e.getChunkSize());
        vo.setOverlap(e.getOverlap());
        vo.setSeparator(e.getSeparator());
        vo.setEmbedModelId(e.getEmbedModelId());
        vo.setRefsJson(e.getRefsJson());
        vo.setRefs(parseRefs(e.getRefsJson()));
        vo.setCiteCnt(e.getCiteCnt());
        vo.setQualityScore(e.getQualityScore());
        vo.setScoreSource(e.getScoreSource());
        vo.setScoreReason(e.getScoreReason());
        vo.setLastAccessAt(e.getLastAccessAt());
        vo.setLifecycleStatus(StrUtil.blankToDefault(e.getLifecycleStatus(), LIFECYCLE_ACTIVE));
        vo.setTagsJson(e.getTagsJson());
        vo.setStatus(e.getStatus());
        vo.setIndexMode(parseIndexTag(e.getRemark(), "indexMode"));
        vo.setIndexError(parseIndexTag(e.getRemark(), "indexError"));
        vo.setWs(e.getWs());
        vo.setScope(effectiveScope(e));
        vo.setRevision(e.getRevision());
        vo.setUpdateTime(e.getUpdateTime());
        Long cnt = chunkMapper.selectCount(new QueryWrapper<GovKbChunk>().lambda()
                .eq(GovKbChunk::getEntryId, e.getId()));
        vo.setChunkCount(cnt == null ? 0 : cnt.intValue());
        if (withChunks) {
            List<GovKbChunk> chunks = chunkMapper.selectList(new QueryWrapper<GovKbChunk>().lambda()
                    .eq(GovKbChunk::getEntryId, e.getId())
                    .orderByAsc(GovKbChunk::getOrdinal));
            vo.setChunks(chunks.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("ordinal", c.getOrdinal());
                m.put("text", c.getTextContent());
                m.put("tokenEst", c.getTokenEst());
                return m;
            }).toList());
        }
        return vo;
    }

    private static long countCat(List<GovKbEntry> all, String cat) {
        return all.stream().filter(e -> cat.equalsIgnoreCase(e.getCat())).count();
    }

    private static String normalizeCat(String cat) {
        String c = StrUtil.blankToDefault(cat, "faq").trim().toLowerCase(Locale.ROOT);
        return switch (c) {
            case "term", "dict", "practice", "faq", "manual", "minutes", "memo" ->
                    "memo".equals(c) ? CAT_MINUTES : c;
            default -> "faq";
        };
    }

    /** scope=platform → 公用库；其余（含空）→ workspace。 */
    private static String normalizeScope(String scope) {
        if (StrUtil.isNotBlank(scope) && SCOPE_PLATFORM.equalsIgnoreCase(scope.trim())) {
            return SCOPE_PLATFORM;
        }
        return SCOPE_WORKSPACE;
    }

    private static String effectiveScope(GovKbEntry entry) {
        if (entry == null) {
            return SCOPE_WORKSPACE;
        }
        if (StrUtil.isNotBlank(entry.getScope())) {
            return normalizeScope(entry.getScope());
        }
        if (WS_PLATFORM.equals(entry.getWs())) {
            return SCOPE_PLATFORM;
        }
        return SCOPE_WORKSPACE;
    }

    private static String resolveRefsJson(GovKbUpsertParam param) {
        if (param == null) {
            return null;
        }
        String cat = normalizeCat(param.getCat());
        if (param.getRefsJson() != null || param.getRefs() != null) {
            String normalized = KbTermRefs.normalizeJson(param.getRefsJson(), param.getRefs(), cat);
            if (normalized != null) {
                return normalized;
            }
        }
        if (param.getRefsJson() != null) {
            return param.getRefsJson();
        }
        if (param.getRefs() != null) {
            if (JSONUtil.isTypeJSON(param.getRefs())) {
                return param.getRefs();
            }
            return JSONUtil.toJsonStr(Map.of("label", param.getRefs()));
        }
        return null;
    }

    @Override
    public Map<String, Object> glossaryGraph(String rootEntryId, Integer depth) {
        return termGraphHelper.graph(rootEntryId, depth == null ? 2 : depth);
    }

    @Override
    public Map<String, Object> glossaryStats() {
        return termGraphHelper.glossaryStats();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rebuildGlossaryEdges() {
        return termGraphHelper.rebuildAllEdges();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ensureTermForMetric(String metricCode) {
        if (StrUtil.isBlank(metricCode)) {
            throw new CommonException("metricCode 不能为空");
        }
        String code = metricCode.trim();
        GovMetric metric = metricMapper.selectOne(new QueryWrapper<GovMetric>().lambda()
                .eq(GovMetric::getDeleteFlag, NOT_DELETE)
                .eq(GovMetric::getMetricCode, code)
                .last("LIMIT 1"));
        if (metric == null) {
            List<GovMetric> all = metricMapper.selectList(new QueryWrapper<GovMetric>().lambda()
                    .eq(GovMetric::getDeleteFlag, NOT_DELETE));
            metric = all.stream()
                    .filter(m -> m.getMetricCode() != null && code.equalsIgnoreCase(m.getMetricCode()))
                    .findFirst()
                    .orElse(null);
        }
        if (metric == null) {
            throw new CommonException("指标不存在: " + metricCode);
        }
        return termGraphHelper.ensureTermForMetric(metric);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ensureTermsForUncovered() {
        Map<String, Object> stats = termGraphHelper.glossaryStats();
        @SuppressWarnings("unchecked")
        List<String> uncovered = (List<String>) stats.getOrDefault("uncoveredMetricCodes", List.of());
        int created = 0;
        int existed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (String code : uncovered) {
            try {
                Map<String, Object> r = ensureTermForMetric(code);
                if (Boolean.TRUE.equals(r.get("created"))) {
                    created++;
                } else {
                    existed++;
                }
            } catch (Exception e) {
                failed++;
                if (errors.size() < 10) {
                    errors.add(code + ": " + e.getMessage());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uncovered", uncovered.size());
        out.put("created", created);
        out.put("existed", existed);
        out.put("failed", failed);
        out.put("errors", errors);
        out.putAll(termGraphHelper.glossaryStats());
        return out;
    }

    private static Object parseRefs(String refsJson) {
        if (StrUtil.isBlank(refsJson)) {
            return null;
        }
        try {
            return JSONUtil.parse(refsJson);
        } catch (Exception e) {
            return refsJson;
        }
    }

    private static String parseIndexTag(String remark, String key) {
        if (StrUtil.isBlank(remark) || StrUtil.isBlank(key)) {
            return null;
        }
        String needle = key + "=";
        int i = remark.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        if (i < 0) {
            return null;
        }
        int start = i + needle.length();
        int end = remark.indexOf(" | ", start);
        if (end < 0) {
            end = remark.length();
        }
        String v = remark.substring(start, end).trim();
        return v.isEmpty() ? null : v;
    }

    private static double scoreKeyword(String q, String text, String title) {
        String t = StrUtil.nullToEmpty(text).toLowerCase(Locale.ROOT);
        String qq = q.toLowerCase(Locale.ROOT);
        double score = 0;
        if (t.contains(qq)) {
            score += 1.0;
            score += Math.min(0.5, qq.length() * 0.02);
        }
        if (StrUtil.containsIgnoreCase(title, q)) {
            score += 0.8;
        }
        return score;
    }

    private static Date truncateDay(Date d) {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }
}
