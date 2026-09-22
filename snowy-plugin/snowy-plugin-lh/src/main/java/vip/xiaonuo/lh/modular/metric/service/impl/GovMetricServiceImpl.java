package vip.xiaonuo.lh.modular.metric.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.enums.CommonSortOrderEnum;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricDep;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricHistory;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricSample;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricSql;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricVer;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricDepMapper;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricHistoryMapper;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMapper;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricSampleMapper;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricSqlMapper;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricVerMapper;
import vip.xiaonuo.lh.modular.metric.param.GovMetricCompileParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricPageParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricQueryParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTransitionParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTrialParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricUpsertParam;
import vip.xiaonuo.lh.modular.metric.result.GovMetricVo;
import vip.xiaonuo.lh.modular.metric.service.GovMetricExecService;
import vip.xiaonuo.lh.modular.metric.service.GovMetricService;
import vip.xiaonuo.lh.modular.metric.support.GovMetricFormulaParser;
import vip.xiaonuo.lh.modular.metric.support.GovMetricSampleCollector;
import vip.xiaonuo.lh.modular.metric.support.GovMetricSqlCompiler;
import vip.xiaonuo.lh.modular.metric.support.MetricAnomalyCalc;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.entity.GovAssetSourceLink;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetSourceLinkMapper;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 指标中心 P0 实现
 */
@Service
public class GovMetricServiceImpl implements GovMetricService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";

    private static final Map<String, String> STATUS_LABEL = Map.of(
            "draft", "草稿",
            "review", "待发布",
            "active", "已启用",
            "version_review", "待发布·变更",
            "deprecated", "已废弃"
    );

    private static final Map<String, String> DOMAIN_MAP = Map.of(
            "交易", "trade",
            "用户", "user",
            "商品", "goods",
            "流量", "user",
            "财务", "trade"
    );

    @Resource
    private GovMetricMapper metricMapper;
    @Resource
    private GovMetricVerMapper verMapper;
    @Resource
    private GovMetricDepMapper depMapper;
    @Resource
    private GovMetricSqlMapper sqlMapper;
    @Resource
    private GovMetricHistoryMapper historyMapper;
    @Resource
    private GovMetricExecService govMetricExecService;
    @Resource
    private GovMetricSampleMapper sampleMapper;
    @Resource
    private GovMetricSampleCollector sampleCollector;
    @Resource
    private GovAssetMapper govAssetMapper;
    @Resource
    private GovAssetSourceLinkMapper govAssetSourceLinkMapper;
    @Resource
    private SecAuthGrantService secAuthGrantService;

    @Override
    public Map<String, Object> overview(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<GovMetric> all = metricMapper.selectList(new QueryWrapper<GovMetric>().lambda()
                .eq(GovMetric::getWs, workspace)
                .eq(GovMetric::getDeleteFlag, NOT_DELETE));
        long atom = all.stream().filter(m -> "原子".equals(m.getKind())).count();
        long derive = all.stream().filter(m -> "衍生".equals(m.getKind())).count();
        long composite = all.stream().filter(m -> "复合".equals(m.getKind())).count();
        long active = all.stream().filter(m -> "active".equals(m.getStatus())).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("atomCount", atom);
        out.put("deriveCount", derive);
        out.put("compositeCount", composite);
        out.put("activeCount", active);
        out.put("ws", workspace);
        return out;
    }

    @Override
    public Page<GovMetricVo> page(GovMetricPageParam param) {
        QueryWrapper<GovMetric> qw = new QueryWrapper<GovMetric>().checkSqlInjection();
        qw.lambda().eq(GovMetric::getDeleteFlag, NOT_DELETE);
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        qw.lambda().eq(GovMetric::getWs, ws);
        if (StrUtil.isNotBlank(param.getDomain()) && !"all".equalsIgnoreCase(param.getDomain())) {
            qw.lambda().eq(GovMetric::getDomainCode, normalizeDomain(param.getDomain()));
        }
        if (StrUtil.isNotBlank(param.getKind()) && !"all".equals(param.getKind())) {
            qw.lambda().eq(GovMetric::getKind, param.getKind().trim());
        }
        if (StrUtil.isNotBlank(param.getStatus()) && !"all".equalsIgnoreCase(param.getStatus())) {
            qw.lambda().eq(GovMetric::getStatus, param.getStatus().trim().toLowerCase(Locale.ROOT));
        }
        if (StrUtil.isNotBlank(param.getQ())) {
            String kw = param.getQ().trim();
            qw.lambda().and(w -> w.like(GovMetric::getMetricCode, kw)
                    .or().like(GovMetric::getName, kw)
                    .or().like(GovMetric::getOwner, kw));
        }
        if (StrUtil.isNotBlank(param.getSortField())) {
            String order = StrUtil.blankToDefault(param.getSortOrder(), CommonSortOrderEnum.DESC.getValue());
            CommonSortOrderEnum.validate(order);
            qw.orderBy(true, order.equalsIgnoreCase(CommonSortOrderEnum.ASC.getValue()),
                    StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByDesc(GovMetric::getUpdateTime).orderByDesc(GovMetric::getCreateTime);
        }
        Page<GovMetric> raw = metricMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<GovMetricVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        out.setRecords(raw.getRecords().stream().map(m -> toVo(m, false)).toList());
        return out;
    }

    @Override
    public GovMetricVo detail(String metricCode, String ws) {
        GovMetric head = requireMetric(metricCode, ws);
        return toVo(head, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovMetricVo create(GovMetricUpsertParam param) {
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        String kind = normalizeKind(param.getKind());
        validateDefinition(kind, param);
        resolveAtomBindAsset(kind, param, ws);
        String code = nextMetricCode(kind, ws);
        GovMetric head = new GovMetric();
        head.setId(IdUtil.getSnowflakeNextIdStr());
        head.setRevision(1);
        head.setStatus("draft");
        head.setWs(ws);
        head.setRemark(param.getRemark());
        head.setMetricCode(code);
        head.setName(param.getName().trim());
        head.setKind(kind);
        head.setDomainCode(normalizeDomain(param.getDomain()));
        head.setUnit(StrUtil.blankToDefault(param.getUnit(), ""));
        head.setOwner(StrUtil.blankToDefault(param.getOwner(), ""));
        head.setCurrentVer("v1");
        head.setGravAssetId(param.getGravAssetId());
        head.setDeleteFlag(NOT_DELETE);
        metricMapper.insert(head);

        GovMetricVer ver = buildVerFromParam(head, "v1", param);
        verMapper.insert(ver);
        head.setCurrentVerId(ver.getId());
        metricMapper.updateById(head);
        replaceDeps(ver.getId(), collectDeps(kind, param, ver));
        appendHistory(head.getId(), "draft", "新建保存为草稿");
        return toVo(requireMetric(code, ws), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovMetricVo update(GovMetricUpsertParam param) {
        if (StrUtil.isBlank(param.getMetricCode())) {
            throw new CommonException("metricCode 不能为空");
        }
        GovMetric head = requireMetric(param.getMetricCode(), param.getWs());
        secAuthGrantService.assertCanEditMetric(head);
        if (!"draft".equals(head.getStatus()) && !"review".equals(head.getStatus())) {
            throw new CommonException("仅草稿/评审中可直接编辑；已启用请走变更");
        }
        String kind = normalizeKind(StrUtil.blankToDefault(param.getKind(), head.getKind()));
        validateDefinition(kind, param);
        resolveAtomBindAsset(kind, param, head.getWs());
        head.setName(StrUtil.blankToDefault(param.getName(), head.getName()).trim());
        head.setKind(kind);
        head.setDomainCode(normalizeDomain(StrUtil.blankToDefault(param.getDomain(), head.getDomainCode())));
        head.setUnit(param.getUnit() != null ? param.getUnit() : head.getUnit());
        head.setOwner(param.getOwner() != null ? param.getOwner() : head.getOwner());
        head.setRemark(param.getRemark() != null ? param.getRemark() : head.getRemark());
        head.setGravAssetId(param.getGravAssetId() != null ? param.getGravAssetId() : head.getGravAssetId());
        head.setRevision(head.getRevision() == null ? 1 : head.getRevision() + 1);
        metricMapper.updateById(head);

        GovMetricVer ver = verMapper.selectById(head.getCurrentVerId());
        if (ver == null) {
            throw new CommonException("当前版本不存在");
        }
        applyParamToVer(ver, param, kind);
        ver.setFingerprint(fingerprint(ver));
        ver.setRevision(ver.getRevision() == null ? 1 : ver.getRevision() + 1);
        verMapper.updateById(ver);
        replaceDeps(ver.getId(), collectDeps(kind, param, ver));
        // 清编译缓存
        sqlMapper.delete(new QueryWrapper<GovMetricSql>().lambda().eq(GovMetricSql::getVerId, ver.getId()));
        appendHistory(head.getId(), head.getStatus(), "编辑口径");
        return toVo(requireMetric(head.getMetricCode(), head.getWs()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovMetricVo transition(GovMetricTransitionParam param) {
        GovMetric head = requireMetric(param.getMetricCode(), param.getWs());
        secAuthGrantService.assertCanEditMetric(head);
        String action = param.getAction().trim();
        String note = StrUtil.blankToDefault(param.getNote(), "");
        String from = head.getStatus();
        String to;
        switch (action) {
            case "submit" -> {
                requireStatus(from, "draft");
                to = "review";
                appendHistory(head.getId(), to, StrUtil.blankToDefault(note, "提交评审"));
            }
            case "approve" -> {
                requireStatus(from, "review");
                to = "active";
                // 启用时编译并缓存
                compileAndPersist(head, "trino");
                appendHistory(head.getId(), to, StrUtil.blankToDefault(note, head.getCurrentVer() + " 启用"));
            }
            case "reject" -> {
                requireStatus(from, "review");
                to = "draft";
                appendHistory(head.getId(), to, StrUtil.blankToDefault(note, "评审退回"));
            }
            case "change" -> {
                requireStatus(from, "active");
                to = "version_review";
                GovMetricVer ver = verMapper.selectById(head.getCurrentVerId());
                if (ver != null) {
                    ver.setPendingCaliber(StrUtil.blankToDefault(note, ver.getCaliber()));
                    verMapper.updateById(ver);
                }
                appendHistory(head.getId(), to, StrUtil.blankToDefault(note, "发起口径变更"));
            }
            case "approveVersion" -> {
                requireStatus(from, "version_review");
                to = "active";
                bumpVersion(head);
                compileAndPersist(head, "trino");
                appendHistory(head.getId(), to, head.getCurrentVer() + " 启用");
            }
            case "cancelChange" -> {
                requireStatus(from, "version_review");
                to = "active";
                GovMetricVer ver = verMapper.selectById(head.getCurrentVerId());
                if (ver != null) {
                    ver.setPendingCaliber(null);
                    verMapper.updateById(ver);
                }
                appendHistory(head.getId(), to, StrUtil.blankToDefault(note, "取消变更，保持当前版本"));
            }
            case "deprecate" -> {
                requireStatus(from, "active");
                to = "deprecated";
                appendHistory(head.getId(), to, StrUtil.blankToDefault(note, "废弃，禁止新引用"));
            }
            default -> throw new CommonException("不支持的操作: " + action);
        }
        head.setStatus(to);
        head.setRevision(head.getRevision() == null ? 1 : head.getRevision() + 1);
        metricMapper.updateById(head);
        return toVo(requireMetric(head.getMetricCode(), head.getWs()), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> compile(GovMetricCompileParam param) {
        String dialect = StrUtil.blankToDefault(param.getDialect(), "trino");
        if (StrUtil.isNotBlank(param.getMetricCode())) {
            GovMetric head = requireMetric(param.getMetricCode(), param.getWs());
            if (Boolean.TRUE.equals(param.getPersist())) {
                secAuthGrantService.assertCanEditMetric(head);
            }
            GovMetricSqlCompiler.CompileOut out = compileInternal(head, dialect);
            if (Boolean.TRUE.equals(param.getPersist()) && StrUtil.isNotBlank(head.getCurrentVerId())) {
                persistSql(head.getCurrentVerId(), out);
            }
            return out.meta();
        }
        if (param.getDraft() == null) {
            throw new CommonException("metricCode 或 draft 至少提供一个");
        }
        // 试编译：内存构造
        GovMetricUpsertParam draft = param.getDraft();
        String kind = normalizeKind(draft.getKind());
        validateDefinition(kind, draft);
        GovMetric head = new GovMetric();
        head.setMetricCode("TMP-0000");
        head.setKind(kind);
        head.setName(draft.getName());
        GovMetricVer ver = new GovMetricVer();
        applyParamToVer(ver, draft, kind);
        ver.setVer("v1");
        GovMetricSqlCompiler.CompileOut out = GovMetricSqlCompiler.compile(head, ver, dialect, code -> resolveNode(code, draft.getWs()));
        return out.meta();
    }

    @Override
    public Map<String, Object> query(GovMetricQueryParam param) {
        return govMetricExecService.query(param);
    }

    @Override
    public Map<String, Object> trial(String metricCode, GovMetricTrialParam param) {
        return govMetricExecService.trial(metricCode, param);
    }

    @Override
    public Map<String, Object> lineage(String metricCode, String ws) {
        GovMetric head = requireMetric(metricCode, ws);
        GovMetricVer ver = verMapper.selectById(head.getCurrentVerId());
        List<GovMetricDep> deps = ver == null ? List.of() : depMapper.selectList(
                new QueryWrapper<GovMetricDep>().lambda().eq(GovMetricDep::getVerId, ver.getId()));
        List<Map<String, Object>> upstream = new ArrayList<>();
        for (GovMetricDep d : deps) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("metricCode", d.getDepCode());
            u.put("depVer", d.getDepVer());
            GovMetric depHead = findMetric(d.getDepCode(), head.getWs());
            if (depHead != null) {
                u.put("name", depHead.getName());
                u.put("kind", depHead.getKind());
                u.put("status", depHead.getStatus());
            }
            upstream.add(u);
        }
        List<GovMetricDep> downEdges = depMapper.selectList(new QueryWrapper<GovMetricDep>().lambda()
                .eq(GovMetricDep::getDepCode, head.getMetricCode()));
        List<Map<String, Object>> downstream = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (GovMetricDep e : downEdges) {
            GovMetricVer dv = verMapper.selectById(e.getVerId());
            if (dv == null) {
                continue;
            }
            GovMetric dh = metricMapper.selectById(dv.getMetricId());
            if (dh == null || !NOT_DELETE.equals(dh.getDeleteFlag()) || !seen.add(dh.getMetricCode())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metricCode", dh.getMetricCode());
            row.put("name", dh.getName());
            row.put("kind", dh.getKind());
            row.put("status", dh.getStatus());
            row.put("ver", dh.getCurrentVer());
            downstream.add(row);
        }
        Map<String, Object> physical = new LinkedHashMap<>();
        if (ver != null) {
            physical.put("bindTable", ver.getBindTable());
            physical.put("bindField", ver.getBindField());
            physical.put("agg", ver.getAgg());
            physical.put("atomRef", ver.getAtomRef());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricCode", head.getMetricCode());
        out.put("upstream", upstream);
        out.put("downstream", downstream);
        out.put("physical", physical);
        return out;
    }

    @Override
    public Map<String, Object> anomaly(String metricCode, String ws, Integer days) {
        GovMetric head = requireMetric(metricCode, ws);
        secAuthGrantService.assertCanReadMetric(head);
        int n = days == null ? 14 : Math.max(1, Math.min(days, 90));
        List<GovMetricSample> samples = sampleMapper.selectList(new QueryWrapper<GovMetricSample>().lambda()
                .eq(GovMetricSample::getWs, StrUtil.blankToDefault(head.getWs(), WS_DEFAULT))
                .eq(GovMetricSample::getMetricCode, head.getMetricCode())
                .orderByDesc(GovMetricSample::getSampleDt)
                .last("LIMIT " + n));
        List<Map<String, Object>> series = new ArrayList<>();
        for (GovMetricSample s : samples) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sampleDt", formatSampleDay(s.getSampleDt()));
            row.put("ver", s.getVer());
            row.put("metricValue", s.getMetricValue());
            row.put("prevValue", s.getPrevValue());
            row.put("changePct", s.getChangePct());
            row.put("anomaly", s.getAnomaly() != null && s.getAnomaly() == 1);
            row.put("thresholdPct", s.getThresholdPct());
            row.put("status", s.getStatus());
            row.put("message", s.getMessage());
            row.put("severity", MetricAnomalyCalc.severity(s.getChangePct(), s.getThresholdPct()));
            series.add(row);
        }
        GovMetricSample latest = samples.isEmpty() ? null : samples.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricCode", head.getMetricCode());
        out.put("name", head.getName());
        out.put("ws", head.getWs());
        out.put("days", n);
        out.put("latest", latest == null ? null : series.get(0));
        out.put("anomaly", latest != null && latest.getAnomaly() != null && latest.getAnomaly() == 1);
        out.put("summary", latest == null ? "尚无采样点；请开启 lh.metric.sample-daily-enabled 或 POST .../anomaly/rerun"
                : latest.getMessage());
        out.put("series", series);
        return out;
    }

    @Override
    public Map<String, Object> sampleRerun(String ws) {
        return sampleCollector.runDaily(ws);
    }

    // —— helpers ——

    private void bumpVersion(GovMetric head) {
        GovMetricVer old = verMapper.selectById(head.getCurrentVerId());
        if (old == null) {
            throw new CommonException("当前版本不存在");
        }
        String nextVer = bumpVer(head.getCurrentVer());
        GovMetricVer neu = new GovMetricVer();
        neu.setId(IdUtil.getSnowflakeNextIdStr());
        neu.setRevision(1);
        neu.setStatus("active");
        neu.setWs(head.getWs());
        neu.setMetricId(head.getId());
        neu.setVer(nextVer);
        neu.setCaliber(StrUtil.blankToDefault(old.getPendingCaliber(), old.getCaliber()));
        neu.setFormula(old.getFormula());
        neu.setAtomRef(old.getAtomRef());
        neu.setAgg(old.getAgg());
        neu.setBindTable(old.getBindTable());
        neu.setBindField(old.getBindField());
        neu.setQualifierJson(old.getQualifierJson());
        neu.setGrainJson(old.getGrainJson());
        neu.setTimeWindow(old.getTimeWindow());
        neu.setPendingCaliber(null);
        neu.setFingerprint(fingerprint(neu));
        neu.setDeleteFlag(NOT_DELETE);
        verMapper.insert(neu);
        // 复制依赖
        List<GovMetricDep> deps = depMapper.selectList(new QueryWrapper<GovMetricDep>().lambda()
                .eq(GovMetricDep::getVerId, old.getId()));
        for (GovMetricDep d : deps) {
            GovMetricDep nd = new GovMetricDep();
            nd.setId(IdUtil.getSnowflakeNextIdStr());
            nd.setVerId(neu.getId());
            nd.setDepCode(d.getDepCode());
            nd.setDepVer(d.getDepVer());
            nd.setCreateTime(new Date());
            depMapper.insert(nd);
        }
        old.setPendingCaliber(null);
        verMapper.updateById(old);
        head.setCurrentVer(nextVer);
        head.setCurrentVerId(neu.getId());
        metricMapper.updateById(head);
    }

    private GovMetricSqlCompiler.CompileOut compileInternal(GovMetric head, String dialect) {
        return GovMetricSqlCompiler.compile(head, requireVer(head), dialect,
                code -> resolveNode(code, head.getWs()));
    }

    private void compileAndPersist(GovMetric head, String dialect) {
        GovMetricSqlCompiler.CompileOut out = compileInternal(head, dialect);
        persistSql(head.getCurrentVerId(), out);
    }

    private void persistSql(String verId, GovMetricSqlCompiler.CompileOut out) {
        GovMetricSql existing = sqlMapper.selectOne(new QueryWrapper<GovMetricSql>().lambda()
                .eq(GovMetricSql::getVerId, verId)
                .eq(GovMetricSql::getDialect, out.dialect())
                .last("LIMIT 1"));
        if (existing == null) {
            existing = new GovMetricSql();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setVerId(verId);
            existing.setDialect(out.dialect());
            existing.setCreateTime(new Date());
            existing.setSqlText(out.sqlText());
            existing.setDepClosure(toJsonArray(out.depClosure()));
            existing.setBindAssets(toJsonArray(out.bindAssets()));
            sqlMapper.insert(existing);
        } else {
            existing.setSqlText(out.sqlText());
            existing.setDepClosure(toJsonArray(out.depClosure()));
            existing.setBindAssets(toJsonArray(out.bindAssets()));
            sqlMapper.updateById(existing);
        }
    }

    private GovMetricSqlCompiler.MetricNode resolveNode(String code, String ws) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        GovMetric h = findMetric(code, ws);
        if (h == null) {
            return null;
        }
        GovMetricVer v = verMapper.selectById(h.getCurrentVerId());
        return new GovMetricSqlCompiler.MetricNode(h.getMetricCode(), h, v);
    }

    private void validateDefinition(String kind, GovMetricUpsertParam param) {
        if ("原子".equals(kind)) {
            if (StrUtil.isBlank(param.getTable()) || StrUtil.isBlank(param.getField()) || StrUtil.isBlank(param.getAgg())) {
                throw new CommonException("原子指标须填写绑定表、字段与聚合");
            }
        } else if ("衍生".equals(kind)) {
            if (StrUtil.isBlank(param.getAtomRef())) {
                throw new CommonException("衍生指标须选择依赖原子");
            }
        } else if ("复合".equals(kind)) {
            GovMetricFormulaParser.Result r = GovMetricFormulaParser.parse(param.getFormula());
            if (!r.ok()) {
                throw new CommonException(r.error());
            }
        }
    }

    /**
     * 原子绑定对齐资产目录：用 assetId / table 反查 gov_asset，回填 gravAssetId；
     * 找不到已登记资产则拒绝（避免演示表名绕过目录）。
     */
    private void resolveAtomBindAsset(String kind, GovMetricUpsertParam param, String ws) {
        if (!"原子".equals(kind)) {
            return;
        }
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        GovAsset asset = null;
        if (StrUtil.isNotBlank(param.getAssetId())) {
            asset = govAssetMapper.selectById(param.getAssetId().trim());
            if (asset == null || !NOT_DELETE.equals(asset.getDeleteFlag())) {
                throw new CommonException("绑定资产不存在: {}", param.getAssetId());
            }
        }
        if (asset == null && StrUtil.isNotBlank(param.getTable())) {
            asset = findAssetByBindTable(param.getTable().trim(), workspace);
        }
        if (asset == null) {
            throw new CommonException("绑定表须来自资产目录已登记表: {}", param.getTable());
        }
        if (StrUtil.isBlank(param.getGravAssetId())) {
            param.setGravAssetId(StrUtil.blankToDefault(asset.getGravAssetId(), asset.getId()));
        }
        // 仅 assetId 入参、未带 table 时用资产主源规范化绑定表
        if (StrUtil.isBlank(param.getTable())) {
            String normalized = normalizeBindTable(asset);
            if (StrUtil.isNotBlank(normalized)) {
                param.setTable(normalized);
            }
        }
    }

    private GovAsset findAssetByBindTable(String table, String ws) {
        // 1) assetCode 精确
        GovAsset byCode = govAssetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getWs, ws)
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(GovAsset::getAssetCode, table)
                .last("LIMIT 1"));
        if (byCode != null) {
            return byCode;
        }
        // 2) om_fqn 精确或末段匹配
        GovAsset byOm = govAssetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getWs, ws)
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(GovAsset::getOmFqn, table).or().likeLeft(GovAsset::getOmFqn, "." + table))
                .last("LIMIT 1"));
        if (byOm != null) {
            return byOm;
        }
        // 3) 主源 object_name
        GovAssetSourceLink link = govAssetSourceLinkMapper.selectOne(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE)
                .eq(GovAssetSourceLink::getObjectName, table)
                .last("LIMIT 1"));
        if (link != null && StrUtil.isNotBlank(link.getAssetId())) {
            GovAsset a = govAssetMapper.selectById(link.getAssetId());
            if (a != null && NOT_DELETE.equals(a.getDeleteFlag())
                    && (StrUtil.isBlank(ws) || ws.equals(a.getWs()))) {
                return a;
            }
        }
        // 4) object_name 末段（table 无 schema 前缀时）
        if (!table.contains(".")) {
            return null;
        }
        String shortName = table.substring(table.lastIndexOf('.') + 1);
        GovAssetSourceLink shortLink = govAssetSourceLinkMapper.selectOne(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(GovAssetSourceLink::getObjectName, shortName)
                        .or().likeLeft(GovAssetSourceLink::getObjectName, "." + shortName)
                        .or().eq(GovAssetSourceLink::getObjectName, table))
                .last("LIMIT 1"));
        if (shortLink != null && StrUtil.isNotBlank(shortLink.getAssetId())) {
            return govAssetMapper.selectById(shortLink.getAssetId());
        }
        return null;
    }

    private String normalizeBindTable(GovAsset asset) {
        if (asset == null) {
            return null;
        }
        GovAssetSourceLink primary = govAssetSourceLinkMapper.selectOne(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getAssetId, asset.getId())
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (primary != null && StrUtil.isNotBlank(primary.getObjectName())) {
            return primary.getObjectName().trim();
        }
        if (StrUtil.isNotBlank(asset.getOmFqn())) {
            String[] parts = asset.getOmFqn().split("\\.");
            if (parts.length >= 2) {
                return parts[parts.length - 2] + "." + parts[parts.length - 1];
            }
            return asset.getOmFqn();
        }
        return asset.getAssetCode();
    }

    private List<String> collectDeps(String kind, GovMetricUpsertParam param, GovMetricVer ver) {
        List<String> deps = new ArrayList<>();
        if ("衍生".equals(kind)) {
            deps.add(StrUtil.blankToDefault(param.getAtomRef(), ver.getAtomRef()));
        } else if ("复合".equals(kind)) {
            if (param.getDeriveRef() != null && !param.getDeriveRef().isEmpty()) {
                deps.addAll(param.getDeriveRef());
            } else {
                GovMetricFormulaParser.Result r = GovMetricFormulaParser.parse(ver.getFormula());
                if (r.ok()) {
                    deps.addAll(r.refs());
                }
            }
        }
        return deps.stream().filter(StrUtil::isNotBlank).distinct().toList();
    }

    private void replaceDeps(String verId, List<String> deps) {
        depMapper.delete(new QueryWrapper<GovMetricDep>().lambda().eq(GovMetricDep::getVerId, verId));
        for (String code : deps) {
            GovMetricDep d = new GovMetricDep();
            d.setId(IdUtil.getSnowflakeNextIdStr());
            d.setVerId(verId);
            d.setDepCode(code.trim().toUpperCase(Locale.ROOT));
            GovMetric dep = findMetric(d.getDepCode(), null);
            if (dep != null) {
                d.setDepVer(dep.getCurrentVer());
            }
            d.setCreateTime(new Date());
            depMapper.insert(d);
        }
    }

    private GovMetricVer buildVerFromParam(GovMetric head, String verNo, GovMetricUpsertParam param) {
        GovMetricVer ver = new GovMetricVer();
        ver.setId(IdUtil.getSnowflakeNextIdStr());
        ver.setRevision(1);
        ver.setStatus("active");
        ver.setWs(head.getWs());
        ver.setMetricId(head.getId());
        ver.setVer(verNo);
        ver.setDeleteFlag(NOT_DELETE);
        applyParamToVer(ver, param, head.getKind());
        ver.setFingerprint(fingerprint(ver));
        return ver;
    }

    private void applyParamToVer(GovMetricVer ver, GovMetricUpsertParam param, String kind) {
        ver.setCaliber(param.getCaliber());
        if ("原子".equals(kind)) {
            ver.setBindTable(param.getTable());
            ver.setBindField(param.getField());
            ver.setAgg(param.getAgg());
            ver.setAtomRef(null);
            ver.setFormula(null);
            ver.setQualifierJson(null);
            ver.setGrainJson(null);
            ver.setTimeWindow(null);
            if (StrUtil.isBlank(ver.getCaliber()) && StrUtil.isNotBlank(param.getAgg())) {
                ver.setCaliber(param.getAgg() + "(" + param.getField() + ")");
            }
        } else if ("衍生".equals(kind)) {
            ver.setAtomRef(param.getAtomRef());
            ver.setQualifierJson(toJsonArray(param.getQualifier()));
            ver.setGrainJson(toJsonArray(param.getDim()));
            ver.setTimeWindow(param.getTime());
            ver.setFormula(null);
            ver.setAgg(null);
            ver.setBindTable(null);
            ver.setBindField(null);
            if (StrUtil.isBlank(ver.getCaliber())) {
                ver.setCaliber(param.getAtomRef() + " · 衍生");
            }
        } else {
            ver.setFormula(param.getFormula());
            ver.setAtomRef(null);
            ver.setAgg(null);
            ver.setBindTable(null);
            ver.setBindField(null);
            ver.setQualifierJson(null);
            ver.setGrainJson(null);
            ver.setTimeWindow(null);
            if (StrUtil.isBlank(ver.getCaliber())) {
                ver.setCaliber(param.getFormula());
            }
        }
    }

    private GovMetricVo toVo(GovMetric head, boolean detail) {
        GovMetricVo vo = new GovMetricVo();
        vo.setId(head.getId());
        vo.setMetricCode(head.getMetricCode());
        vo.setName(head.getName());
        vo.setKind(head.getKind());
        vo.setType(head.getKind());
        vo.setDomainCode(head.getDomainCode());
        vo.setDomain(head.getDomainCode());
        vo.setStatus(head.getStatus());
        vo.setStatusLabel(STATUS_LABEL.getOrDefault(head.getStatus(), head.getStatus()));
        vo.setUnit(head.getUnit());
        vo.setOwner(head.getOwner());
        vo.setVer(head.getCurrentVer());
        vo.setCurrentVerId(head.getCurrentVerId());
        vo.setOmFqn(head.getOmFqn());
        vo.setGravAssetId(head.getGravAssetId());
        vo.setRevision(head.getRevision());
        vo.setUpdateTime(head.getUpdateTime());

        GovMetricVer ver = StrUtil.isBlank(head.getCurrentVerId()) ? null : verMapper.selectById(head.getCurrentVerId());
        if (ver != null) {
            vo.setCaliber(ver.getCaliber());
            vo.setPendingCaliber(ver.getPendingCaliber());
            vo.setFormula(ver.getFormula());
            vo.setAtomRef(ver.getAtomRef());
            vo.setAgg(ver.getAgg());
            vo.setTable(ver.getBindTable());
            vo.setField(ver.getBindField());
            vo.setQualifierKeys(GovMetricSqlCompiler.parseJsonArray(ver.getQualifierJson()));
            vo.setQualifier(vo.getQualifierKeys().isEmpty() ? "无限定" : String.join(" AND ", vo.getQualifierKeys()));
            vo.setDimKeys(GovMetricSqlCompiler.parseJsonArray(ver.getGrainJson()));
            vo.setDim(vo.getDimKeys().isEmpty() ? "" : String.join(" + ", vo.getDimKeys()));
            vo.setTime(ver.getTimeWindow());
            vo.setBind(buildBind(head.getKind(), ver));
            if (StrUtil.isNotBlank(ver.getFormula())) {
                GovMetricFormulaParser.Result r = GovMetricFormulaParser.parse(ver.getFormula());
                if (r.ok()) {
                    Map<String, Object> ast = new LinkedHashMap<>();
                    ast.put("expr", r.expr());
                    ast.put("filter", r.filter());
                    ast.put("refs", r.refs());
                    vo.setFormulaAst(ast);
                }
            }
        }
        List<GovMetricDep> deps = ver == null ? List.of() : depMapper.selectList(
                new QueryWrapper<GovMetricDep>().lambda().eq(GovMetricDep::getVerId, ver.getId()));
        vo.setDepCodes(deps.stream().map(GovMetricDep::getDepCode).collect(Collectors.toList()));

        if (detail) {
            List<GovMetricHistory> hist = historyMapper.selectList(new QueryWrapper<GovMetricHistory>().lambda()
                    .eq(GovMetricHistory::getMetricId, head.getId())
                    .orderByAsc(GovMetricHistory::getCreateTime));
            vo.setHistory(hist.stream().map(h -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", h.getStatus());
                m.put("label", h.getLabel());
                m.put("note", h.getNote());
                m.put("time", h.getCreateTime());
                return m;
            }).toList());
            GovMetricSql sql = ver == null ? null : sqlMapper.selectOne(new QueryWrapper<GovMetricSql>().lambda()
                    .eq(GovMetricSql::getVerId, ver.getId())
                    .eq(GovMetricSql::getDialect, "trino")
                    .last("LIMIT 1"));
            if (sql != null) {
                vo.setCompiledSql(sql.getSqlText());
                vo.setDialect(sql.getDialect());
            }
        }
        return vo;
    }

    private String buildBind(String kind, GovMetricVer ver) {
        if ("原子".equals(kind)) {
            return StrUtil.blankToDefault(ver.getBindTable(), "") + "." + StrUtil.blankToDefault(ver.getBindField(), "");
        }
        if ("衍生".equals(kind)) {
            return StrUtil.blankToDefault(ver.getAtomRef(), "—");
        }
        return StrUtil.blankToDefault(ver.getFormula(), "—");
    }

    private void appendHistory(String metricId, String status, String note) {
        GovMetricHistory h = new GovMetricHistory();
        h.setId(IdUtil.getSnowflakeNextIdStr());
        h.setMetricId(metricId);
        h.setStatus(status);
        h.setLabel(STATUS_LABEL.getOrDefault(status, status));
        h.setNote(note);
        h.setCreateTime(new Date());
        historyMapper.insert(h);
    }

    private GovMetric requireMetric(String code, String ws) {
        GovMetric m = findMetric(code, ws);
        if (m == null) {
            throw new CommonException("指标不存在: " + code);
        }
        return m;
    }

    private GovMetric findMetric(String code, String ws) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        QueryWrapper<GovMetric> qw = new QueryWrapper<>();
        qw.lambda().eq(GovMetric::getDeleteFlag, NOT_DELETE)
                .eq(GovMetric::getMetricCode, code.trim().toUpperCase(Locale.ROOT));
        if (StrUtil.isNotBlank(ws)) {
            qw.lambda().eq(GovMetric::getWs, ws);
        }
        return metricMapper.selectOne(qw.last("LIMIT 1"));
    }

    private GovMetricVer requireVer(GovMetric head) {
        GovMetricVer ver = verMapper.selectById(head.getCurrentVerId());
        if (ver == null) {
            throw new CommonException("指标版本不存在");
        }
        return ver;
    }

    private void requireStatus(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new CommonException("当前状态不允许该操作: " + actual);
        }
    }

    private String nextMetricCode(String kind, String ws) {
        String prefix = switch (kind) {
            case "原子" -> "A";
            case "复合" -> "C";
            default -> "M";
        };
        List<GovMetric> list = metricMapper.selectList(new QueryWrapper<GovMetric>().lambda()
                .eq(GovMetric::getWs, ws)
                .eq(GovMetric::getDeleteFlag, NOT_DELETE)
                .likeRight(GovMetric::getMetricCode, prefix + "-"));
        int max = 0;
        for (GovMetric m : list) {
            String[] parts = m.getMetricCode().split("-");
            if (parts.length == 2) {
                try {
                    max = Math.max(max, Integer.parseInt(parts[1]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return prefix + "-" + String.format("%04d", max + 1);
    }

    private static String normalizeKind(String kind) {
        String k = StrUtil.blankToDefault(kind, "原子").trim();
        if (Set.of("原子", "衍生", "复合").contains(k)) {
            return k;
        }
        return switch (k.toLowerCase(Locale.ROOT)) {
            case "atom", "atomic" -> "原子";
            case "derive", "derived" -> "衍生";
            case "composite", "compound" -> "复合";
            default -> throw new CommonException("未知指标类型: " + kind);
        };
    }

    private static String normalizeDomain(String domain) {
        if (StrUtil.isBlank(domain)) {
            return "trade";
        }
        String d = domain.trim();
        if (DOMAIN_MAP.containsKey(d)) {
            return DOMAIN_MAP.get(d);
        }
        return d.toLowerCase(Locale.ROOT);
    }

    private static String formatSampleDay(Date d) {
        if (d == null) {
            return null;
        }
        if (d instanceof java.sql.Date sql) {
            return sql.toLocalDate().toString();
        }
        return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }

    private static String bumpVer(String ver) {
        if (StrUtil.isBlank(ver)) {
            return "v1";
        }
        String digits = ver.replaceAll("[^0-9]", "");
        int n = StrUtil.isBlank(digits) ? 0 : Integer.parseInt(digits);
        return "v" + (n + 1);
    }

    private static String fingerprint(GovMetricVer ver) {
        String raw = StrUtil.nullToEmpty(ver.getCaliber()) + "|"
                + StrUtil.nullToEmpty(ver.getFormula()) + "|"
                + StrUtil.nullToEmpty(ver.getAtomRef()) + "|"
                + StrUtil.nullToEmpty(ver.getAgg()) + "|"
                + StrUtil.nullToEmpty(ver.getBindTable()) + "|"
                + StrUtil.nullToEmpty(ver.getBindField()) + "|"
                + StrUtil.nullToEmpty(ver.getQualifierJson()) + "|"
                + StrUtil.nullToEmpty(ver.getGrainJson()) + "|"
                + StrUtil.nullToEmpty(ver.getTimeWindow());
        return DigestUtil.md5Hex(raw);
    }

    private static String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(list.get(i).replace("\"", "\\\"")).append('"');
        }
        sb.append(']');
        return sb.toString();
    }
}
