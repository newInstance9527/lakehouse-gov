package vip.xiaonuo.lh.modular.catalog.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.link.LhModuleDeepLinks;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcTableStat;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcPolicyMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcTableStatMapper;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.metric.support.GovMetricBindLookup;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRuleRun;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleRunMapper;
import vip.xiaonuo.lh.modular.standard.entity.GovStdDetectResult;
import vip.xiaonuo.lh.modular.standard.entity.GovStdMapping;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdDetectResultMapper;
import vip.xiaonuo.lh.modular.standard.mapper.GovStdMappingMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 资产详情跨模块 extras：质量分/失败规则 + 血缘影响摘要 + 标准覆盖率（soft-fail）
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Component
public class GovAssetCrossModuleExtras {

    private static final Logger log = LoggerFactory.getLogger(GovAssetCrossModuleExtras.class);
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovDqRuleMapper ruleMapper;
    @Resource
    private GovDqRuleRunMapper runMapper;
    @Resource
    private GovLineageService govLineageService;
    @Resource
    private GovStdMappingMapper mappingMapper;
    @Resource
    private GovStdDetectResultMapper detectMapper;
    @Resource
    private GovLcPolicyMapper lcPolicyMapper;
    @Resource
    private GovLcTableStatMapper lcTableStatMapper;
    @Resource
    private GovMetricBindLookup metricBindLookup;

    public Map<String, Object> buildQuality(GovAsset asset, String objectName) {
        Map<String, Object> out = new LinkedHashMap<>();
        String omFqn = asset == null ? null : asset.getOmFqn();
        String assetId = asset == null ? null : asset.getId();
        String ws = asset == null ? "default" : StrUtil.blankToDefault(asset.getWs(), "default");
        String tableHint = StrUtil.blankToDefault(objectName, shortName(omFqn));
        out.put("path", LhModuleDeepLinks.quality(tableHint));
        try {
            List<GovDqRule> matched = matchRules(ws, assetId, objectName, omFqn);
            if (matched.isEmpty()) {
                out.put("available", true);
                out.put("score", null);
                out.put("failRules", List.of());
                out.put("ruleCount", 0);
                out.put("hint", "无关联质量规则");
                return out;
            }
            List<Map<String, Object>> fails = new ArrayList<>();
            List<BigDecimal> scores = new ArrayList<>();
            for (GovDqRule rule : matched) {
                GovDqRuleRun latest = latestRun(rule.getId());
                if (latest == null) {
                    continue;
                }
                if (latest.getOkPct() != null) {
                    scores.add(latest.getOkPct());
                }
                boolean pass = Integer.valueOf(1).equals(latest.getPass());
                if (!pass) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("ruleId", rule.getId());
                    f.put("ruleCode", rule.getRuleCode());
                    f.put("fieldName", rule.getFieldName());
                    f.put("tableName", rule.getTableName());
                    f.put("severity", rule.getSeverity());
                    f.put("ranAt", latest.getRanAt());
                    f.put("okPct", latest.getOkPct());
                    f.put("message", latest.getMessage());
                    f.put("lineagePath", LhModuleDeepLinks.lineageField(
                            StrUtil.blankToDefault(rule.getTableName(), tableHint),
                            rule.getFieldName()));
                    fails.add(f);
                }
            }
            BigDecimal score = null;
            if (!scores.isEmpty()) {
                BigDecimal sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                score = sum.divide(BigDecimal.valueOf(scores.size()), 1, RoundingMode.HALF_UP);
            }
            out.put("available", true);
            out.put("score", score);
            out.put("ruleCount", matched.size());
            out.put("failRules", fails.size() > 8 ? fails.subList(0, 8) : fails);
            return out;
        } catch (Exception e) {
            log.warn("catalog extras.quality soft-fail asset={}: {}", assetId, e.getMessage());
            out.put("available", false);
            out.put("score", null);
            out.put("failRules", List.of());
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    public Map<String, Object> buildLineage(GovAsset asset, String objectName) {
        Map<String, Object> out = new LinkedHashMap<>();
        String omFqn = asset == null ? null : asset.getOmFqn();
        String focus = StrUtil.blankToDefault(objectName, shortName(omFqn));
        out.put("focus", focus);
        out.put("omFqn", omFqn);
        out.put("path", LhModuleDeepLinks.lineage(focus, omFqn, null, null));
        if (StrUtil.isBlank(focus) && StrUtil.isBlank(omFqn)) {
            out.put("available", false);
            out.put("upCount", 0);
            out.put("downCount", 0);
            out.put("message", "资产无 objectName/omFqn");
            return out;
        }
        try {
            String ws = asset == null ? "default" : StrUtil.blankToDefault(asset.getWs(), "default");
            Map<String, Object> impact = govLineageService.impact(null, focus, omFqn, 2, 2, ws);
            List<?> up = impact == null ? List.of() : (List<?>) impact.getOrDefault("up", List.of());
            List<?> down = impact == null ? List.of() : (List<?>) impact.getOrDefault("down", List.of());
            out.put("available", true);
            out.put("upCount", up.size());
            out.put("downCount", down.size());
            out.put("source", impact == null ? null : impact.get("source"));
            List<Map<String, Object>> downPreview = new ArrayList<>();
            LinkedHashSet<String> owners = new LinkedHashSet<>();
            for (Object o : down) {
                if (!(o instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", raw.get("key"));
                item.put("type", raw.get("type"));
                item.put("owner", raw.get("owner"));
                item.put("assetId", raw.get("assetId"));
                item.put("jobName", raw.get("jobName"));
                downPreview.add(item);
                Object owner = raw.get("owner");
                if (owner != null && StrUtil.isNotBlank(String.valueOf(owner))) {
                    owners.add(String.valueOf(owner));
                }
                if (downPreview.size() >= 8) {
                    break;
                }
            }
            out.put("downPreview", downPreview);
            out.put("owners", List.copyOf(owners));
            return out;
        } catch (Exception e) {
            log.warn("catalog extras.lineage soft-fail focus={}: {}", focus, e.getMessage());
            out.put("available", false);
            out.put("upCount", 0);
            out.put("downCount", 0);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 被引用指标：读 gov_metric / gov_metric_ver.bind_table（及 grav_asset_id）。
     */
    public Map<String, Object> buildMetrics(GovAsset asset, String objectName) {
        Map<String, Object> out = new LinkedHashMap<>();
        String assetId = asset == null ? null : asset.getId();
        String ws = asset == null ? "default" : StrUtil.blankToDefault(asset.getWs(), "default");
        String tableHint = StrUtil.blankToDefault(objectName, shortName(asset == null ? null : asset.getOmFqn()));
        out.put("path", LhModuleDeepLinks.metrics(null));
        try {
            List<Map<String, Object>> refs = metricBindLookup.metricsBoundToAsset(
                    ws,
                    assetId,
                    objectName,
                    asset == null ? null : asset.getAssetCode(),
                    asset == null ? null : asset.getOmFqn());
            out.put("available", true);
            out.put("count", refs.size());
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> ref : refs) {
                Map<String, Object> item = new LinkedHashMap<>(ref);
                Object code = ref.get("metricCode");
                if (code != null) {
                    item.put("path", LhModuleDeepLinks.metrics(String.valueOf(code)));
                }
                items.add(item);
                if (items.size() >= 12) {
                    break;
                }
            }
            out.put("items", items);
            if (refs.isEmpty()) {
                out.put("hint", "无指标绑定本表");
            }
            return out;
        } catch (Exception e) {
            log.warn("catalog extras.metrics soft-fail asset={}: {}", assetId, e.getMessage());
            out.put("available", false);
            out.put("count", 0);
            out.put("items", List.of());
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 标准覆盖率：资产列 ∩（映射目标/源字段 ∪ 检测字段） / 列数。
     * 无 schema 列时回退为「有映射即提示映射条数」，coveragePct 可为 null。
     */
    public Map<String, Object> buildStandard(GovAsset asset, String objectName, Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        String assetId = asset == null ? null : asset.getId();
        String ws = asset == null ? "default" : StrUtil.blankToDefault(asset.getWs(), "default");
        String tableHint = StrUtil.blankToDefault(objectName, shortName(asset == null ? null : asset.getOmFqn()));
        String assetCode = asset == null ? null : asset.getAssetCode();
        out.put("path", LhModuleDeepLinks.standardMapping(tableHint));
        try {
            List<String> columns = columnNames(schema);
            List<GovStdMapping> maps = matchMappings(ws, assetId, tableHint, assetCode);
            List<GovStdDetectResult> detects = matchDetects(ws, assetId, tableHint);

            Set<String> mappedKeys = new LinkedHashSet<>();
            for (GovStdMapping m : maps) {
                if (StrUtil.isNotBlank(m.getStdFieldName())) {
                    mappedKeys.add(m.getStdFieldName().trim());
                }
                if (StrUtil.isNotBlank(m.getSrcField())) {
                    mappedKeys.add(m.getSrcField().trim());
                }
            }
            Set<String> detectFields = new LinkedHashSet<>();
            int detectFail = 0;
            int detectWarn = 0;
            for (GovStdDetectResult d : detects) {
                if (StrUtil.isNotBlank(d.getFieldName())) {
                    detectFields.add(d.getFieldName().trim());
                }
                String st = StrUtil.blankToDefault(d.getStatus(), "").toLowerCase(Locale.ROOT);
                if ("fail".equals(st)) {
                    detectFail++;
                } else if ("warn".equals(st)) {
                    detectWarn++;
                }
            }

            Set<String> covered = new LinkedHashSet<>();
            List<String> gaps = new ArrayList<>();
            Integer coveragePct;
            if (!columns.isEmpty()) {
                Set<String> colLower = new HashSet<>();
                Map<String, String> colCanon = new LinkedHashMap<>();
                for (String c : columns) {
                    colLower.add(c.toLowerCase(Locale.ROOT));
                    colCanon.put(c.toLowerCase(Locale.ROOT), c);
                }
                for (String k : mappedKeys) {
                    String hit = colCanon.get(k.toLowerCase(Locale.ROOT));
                    if (hit != null) {
                        covered.add(hit);
                    }
                }
                for (String k : detectFields) {
                    String hit = colCanon.get(k.toLowerCase(Locale.ROOT));
                    if (hit != null) {
                        covered.add(hit);
                    }
                }
                for (String c : columns) {
                    if (!covered.contains(c)) {
                        gaps.add(c);
                    }
                }
                coveragePct = (int) Math.round(covered.size() * 100.0 / columns.size());
            } else if (!maps.isEmpty()) {
                coveragePct = null;
                out.put("hint", "无列结构；已登记 " + maps.size() + " 条标准映射");
            } else {
                coveragePct = columns.isEmpty() && maps.isEmpty() ? null : 0;
                out.put("hint", "无关联标准映射");
            }

            out.put("available", true);
            out.put("coveragePct", coveragePct);
            out.put("columnCount", columns.size());
            out.put("coveredCount", covered.size());
            out.put("mappingCount", maps.size());
            out.put("detectCount", detects.size());
            out.put("detectFailCount", detectFail);
            out.put("detectWarnCount", detectWarn);
            out.put("gaps", gaps.size() > 12 ? gaps.subList(0, 12) : gaps);
            out.put("gapCount", gaps.size());
            return out;
        } catch (Exception e) {
            log.warn("catalog extras.standard soft-fail asset={}: {}", assetId, e.getMessage());
            out.put("available", false);
            out.put("coveragePct", null);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    /**
     * 生命周期策略摘要标签 + 存储画像（读 {@code gov_lc_policy}/{@code gov_lc_table_stat}）。
     */
    public Map<String, Object> buildLifecycle(GovAsset asset, String objectName) {
        Map<String, Object> out = new LinkedHashMap<>();
        String assetId = asset == null ? null : asset.getId();
        String ws = asset == null ? "default" : StrUtil.blankToDefault(asset.getWs(), "default");
        String tableHint = StrUtil.blankToDefault(objectName, shortName(asset == null ? null : asset.getOmFqn()));
        if (StrUtil.isBlank(tableHint) && asset != null) {
            tableHint = StrUtil.blankToDefault(asset.getAssetCode(), asset.getName());
        }
        out.put("path", LhModuleDeepLinks.lifecycle(tableHint));
        try {
            GovLcPolicy policy = findLcPolicy(ws, tableHint, asset == null ? null : asset.getOmFqn(),
                    asset == null ? null : asset.getAssetCode());
            GovLcTableStat stat = findLcStat(ws, tableHint, asset == null ? null : asset.getOmFqn(),
                    asset == null ? null : asset.getAssetCode());

            List<String> tags = new ArrayList<>();
            Map<String, Object> policyMap = null;
            if (policy != null) {
                policyMap = new LinkedHashMap<>();
                policyMap.put("tableFqn", policy.getTableFqn());
                policyMap.put("keepDays", policy.getKeepDays());
                policyMap.put("keepCount", policy.getKeepCount());
                policyMap.put("compactLevel", policy.getCompactLevel());
                policyMap.put("orphanOlderDays", policy.getOrphanOlderDays());
                policyMap.put("targetFileMb", policy.getTargetFileMb());
                if (policy.getKeepDays() != null) {
                    tags.add("快照" + policy.getKeepDays() + "d");
                }
                if (StrUtil.isNotBlank(policy.getCompactLevel())) {
                    tags.add("compact:" + policy.getCompactLevel());
                }
                if (policy.getOrphanOlderDays() != null) {
                    tags.add("orphan≥" + policy.getOrphanOlderDays() + "d");
                }
            }

            Map<String, Object> profile = null;
            if (stat != null) {
                profile = new LinkedHashMap<>();
                profile.put("tableFqn", stat.getTableFqn());
                profile.put("sizeBytes", stat.getSizeBytes());
                profile.put("activeBytes", stat.getActiveBytes());
                profile.put("fileCount", stat.getFileCount());
                profile.put("avgFileBytes", stat.getAvgFileBytes());
                profile.put("smallFileCount", stat.getSmallFileCount());
                profile.put("smallFileRatio", stat.getSmallFileRatio());
                profile.put("policyLabel", stat.getPolicyLabel());
                profile.put("collectStatus", stat.getCollectStatus());
                profile.put("collectedAt", stat.getCollectedAt());
                if (StrUtil.isNotBlank(stat.getPolicyLabel())) {
                    tags.add(stat.getPolicyLabel());
                }
                if (stat.getSmallFileRatio() != null
                        && stat.getSmallFileRatio().doubleValue() >= 0.3) {
                    tags.add("小文件偏高");
                }
                if ("ok".equalsIgnoreCase(StrUtil.blankToDefault(stat.getCollectStatus(), ""))) {
                    tags.add("画像已采");
                } else if (StrUtil.isNotBlank(stat.getCollectStatus())) {
                    tags.add("画像:" + stat.getCollectStatus());
                }
            }

            out.put("available", true);
            out.put("tableHint", tableHint);
            out.put("policy", policyMap);
            out.put("profile", profile);
            out.put("tags", tags.size() > 8 ? tags.subList(0, 8) : tags);
            if (policy == null && stat == null) {
                out.put("hint", "无生命周期策略/存储画像");
            }
            return out;
        } catch (Exception e) {
            log.warn("catalog extras.lifecycle soft-fail asset={}: {}", assetId, e.getMessage());
            out.put("available", false);
            out.put("tags", List.of());
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    private GovLcPolicy findLcPolicy(String ws, String objectName, String omFqn, String assetCode) {
        List<String> keys = fqnCandidates(objectName, omFqn, assetCode);
        if (keys.isEmpty()) {
            return null;
        }
        List<GovLcPolicy> all = lcPolicyMapper.selectList(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(GovLcPolicy::getWs, StrUtil.blankToDefault(ws, "default"))
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE));
        for (String key : keys) {
            for (GovLcPolicy p : all) {
                if (fqnMatch(p.getTableFqn(), key)) {
                    return p;
                }
            }
        }
        return null;
    }

    private GovLcTableStat findLcStat(String ws, String objectName, String omFqn, String assetCode) {
        List<String> keys = fqnCandidates(objectName, omFqn, assetCode);
        if (keys.isEmpty()) {
            return null;
        }
        List<GovLcTableStat> all = lcTableStatMapper.selectList(new QueryWrapper<GovLcTableStat>().lambda()
                .eq(GovLcTableStat::getWs, StrUtil.blankToDefault(ws, "default"))
                .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE));
        for (String key : keys) {
            for (GovLcTableStat s : all) {
                if (fqnMatch(s.getTableFqn(), key)) {
                    return s;
                }
            }
        }
        return null;
    }

    static List<String> fqnCandidates(String objectName, String omFqn, String assetCode) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(objectName)) {
            keys.add(objectName.trim());
            keys.add(shortName(objectName));
        }
        if (StrUtil.isNotBlank(omFqn)) {
            keys.add(omFqn.trim());
            keys.add(shortName(omFqn));
        }
        if (StrUtil.isNotBlank(assetCode)) {
            keys.add(assetCode.trim());
            keys.add(shortName(assetCode));
        }
        return List.copyOf(keys);
    }

    static boolean fqnMatch(String tableFqn, String key) {
        if (StrUtil.isBlank(tableFqn) || StrUtil.isBlank(key)) {
            return false;
        }
        if (tableFqn.equalsIgnoreCase(key)) {
            return true;
        }
        return shortName(tableFqn).equalsIgnoreCase(shortName(key))
                || tableFqn.toLowerCase(Locale.ROOT).endsWith("." + key.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private static List<String> columnNames(Map<String, Object> schema) {
        if (schema == null) {
            return List.of();
        }
        Object cols = schema.get("columns");
        if (!(cols instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object n = m.get("name");
                if (n != null && StrUtil.isNotBlank(String.valueOf(n))) {
                    names.add(String.valueOf(n).trim());
                }
            }
        }
        return names;
    }

    List<GovStdMapping> matchMappings(String ws, String assetId, String objectName, String assetCode) {
        List<GovStdMapping> all = mappingMapper.selectList(new QueryWrapper<GovStdMapping>().lambda()
                .eq(GovStdMapping::getWs, StrUtil.blankToDefault(ws, "default"))
                .eq(GovStdMapping::getDeleteFlag, NOT_DELETE));
        String shortObj = shortName(objectName);
        return all.stream()
                .filter(m -> mappingMatches(m, assetId, objectName, shortObj, assetCode))
                .collect(Collectors.toList());
    }

    static boolean mappingMatches(GovStdMapping m, String assetId, String objectName,
                                  String shortObj, String assetCode) {
        if (m == null) {
            return false;
        }
        if (StrUtil.isNotBlank(assetId) && Objects.equals(assetId, m.getAssetId())) {
            return true;
        }
        if (eqIgnore(m.getTargetTable(), objectName) || eqIgnore(m.getTargetTable(), shortObj)
                || eqIgnore(m.getTargetTable(), assetCode)) {
            return true;
        }
        if (eqIgnore(m.getSrcObject(), objectName) || eqIgnore(m.getSrcObject(), shortObj)
                || eqIgnore(shortName(m.getSrcObject()), shortObj)) {
            return true;
        }
        String ttShort = shortName(m.getTargetTable());
        return eqIgnore(ttShort, shortObj) || eqIgnore(ttShort, shortName(assetCode));
    }

    List<GovStdDetectResult> matchDetects(String ws, String assetId, String objectName) {
        List<GovStdDetectResult> all = detectMapper.selectList(new QueryWrapper<GovStdDetectResult>().lambda()
                .eq(GovStdDetectResult::getWs, StrUtil.blankToDefault(ws, "default")));
        String shortObj = shortName(objectName);
        return all.stream()
                .filter(d -> detectMatches(d, assetId, objectName, shortObj))
                .collect(Collectors.toList());
    }

    static boolean detectMatches(GovStdDetectResult d, String assetId, String objectName, String shortObj) {
        if (d == null) {
            return false;
        }
        if (StrUtil.isNotBlank(assetId) && Objects.equals(assetId, d.getAssetId())) {
            return true;
        }
        return eqIgnore(d.getTableName(), objectName)
                || eqIgnore(d.getTableName(), shortObj)
                || eqIgnore(shortName(d.getTableName()), shortObj);
    }

    /**
     * 列表批量质量分：一次拉规则 + 一次拉 runs，避免 N+1。
     *
     * @param keys assetId → {objectName, omFqn}
     * @return assetId → score（无匹配规则或不含 run 则不出现）
     */
    public Map<String, BigDecimal> batchScores(String ws, Map<String, AssetScoreKey> keys) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) {
            return out;
        }
        try {
            String workspace = StrUtil.blankToDefault(ws, "default");
            List<GovDqRule> all = ruleMapper.selectList(new QueryWrapper<GovDqRule>().lambda()
                    .eq(GovDqRule::getWs, workspace)
                    .eq(GovDqRule::getDeleteFlag, NOT_DELETE)
                    .eq(GovDqRule::getEnabled, 1));
            if (all.isEmpty()) {
                return out;
            }
            Map<String, List<String>> assetRuleIds = new LinkedHashMap<>();
            for (Map.Entry<String, AssetScoreKey> e : keys.entrySet()) {
                AssetScoreKey k = e.getValue() == null ? new AssetScoreKey(null, null) : e.getValue();
                String shortOm = shortName(k.omFqn);
                List<String> matched = all.stream()
                        .filter(r -> matches(r, e.getKey(), k.objectName, k.omFqn, shortOm))
                        .map(GovDqRule::getId)
                        .filter(StrUtil::isNotBlank)
                        .distinct()
                        .toList();
                if (!matched.isEmpty()) {
                    assetRuleIds.put(e.getKey(), matched);
                }
            }
            if (assetRuleIds.isEmpty()) {
                return out;
            }
            List<String> allRuleIds = assetRuleIds.values().stream()
                    .flatMap(List::stream).distinct().toList();
            Map<String, GovDqRuleRun> latestByRule = latestRunsByRuleIds(allRuleIds);
            for (Map.Entry<String, List<String>> e : assetRuleIds.entrySet()) {
                List<BigDecimal> scores = new ArrayList<>();
                for (String rid : e.getValue()) {
                    GovDqRuleRun run = latestByRule.get(rid);
                    if (run != null && run.getOkPct() != null) {
                        scores.add(run.getOkPct());
                    }
                }
                if (!scores.isEmpty()) {
                    BigDecimal sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    out.put(e.getKey(), sum.divide(BigDecimal.valueOf(scores.size()), 1, RoundingMode.HALF_UP));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("catalog batchScores soft-fail ws={}: {}", ws, e.getMessage());
            return out;
        }
    }

    /** 列表批量质量分入参 */
    public record AssetScoreKey(String objectName, String omFqn) {
    }

    private Map<String, GovDqRuleRun> latestRunsByRuleIds(List<String> ruleIds) {
        Map<String, GovDqRuleRun> latest = new LinkedHashMap<>();
        if (ruleIds == null || ruleIds.isEmpty()) {
            return latest;
        }
        List<GovDqRuleRun> runs = runMapper.selectList(new QueryWrapper<GovDqRuleRun>().lambda()
                .in(GovDqRuleRun::getRuleId, ruleIds)
                .orderByDesc(GovDqRuleRun::getRanAt));
        for (GovDqRuleRun run : runs) {
            if (run == null || StrUtil.isBlank(run.getRuleId())) {
                continue;
            }
            latest.putIfAbsent(run.getRuleId(), run);
        }
        return latest;
    }

    /** 供单测：匹配规则 */
    List<GovDqRule> matchRules(String ws, String assetId, String objectName, String omFqn) {
        List<GovDqRule> all = ruleMapper.selectList(new QueryWrapper<GovDqRule>().lambda()
                .eq(GovDqRule::getWs, StrUtil.blankToDefault(ws, "default"))
                .eq(GovDqRule::getDeleteFlag, NOT_DELETE)
                .eq(GovDqRule::getEnabled, 1));
        String shortOm = shortName(omFqn);
        return all.stream().filter(r -> matches(r, assetId, objectName, omFqn, shortOm)).collect(Collectors.toList());
    }

    static boolean matches(GovDqRule r, String assetId, String objectName, String omFqn, String shortOm) {
        if (r == null) {
            return false;
        }
        if (StrUtil.isNotBlank(assetId) && Objects.equals(assetId, r.getAssetId())) {
            return true;
        }
        String tn = StrUtil.blankToDefault(r.getTableName(), "").trim();
        if (StrUtil.isBlank(tn)) {
            return false;
        }
        if (eqIgnore(tn, objectName) || eqIgnore(tn, omFqn) || eqIgnore(tn, shortOm)) {
            return true;
        }
        String tnShort = shortName(tn);
        return eqIgnore(tnShort, shortName(objectName)) || eqIgnore(tnShort, shortOm);
    }

    private GovDqRuleRun latestRun(String ruleId) {
        return runMapper.selectOne(new QueryWrapper<GovDqRuleRun>().lambda()
                .eq(GovDqRuleRun::getRuleId, ruleId)
                .orderByDesc(GovDqRuleRun::getRanAt)
                .last("LIMIT 1"));
    }

    private static boolean eqIgnore(String a, String b) {
        if (StrUtil.isBlank(a) || StrUtil.isBlank(b)) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static String shortName(String fqnOrName) {
        if (StrUtil.isBlank(fqnOrName)) {
            return null;
        }
        String s = fqnOrName.trim();
        int dot = s.lastIndexOf('.');
        return dot >= 0 && dot < s.length() - 1 ? s.substring(dot + 1) : s;
    }
}
