package vip.xiaonuo.lh.modular.catalog.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.link.LhModuleDeepLinks;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRuleRun;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleRunMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 资产详情跨模块 extras：质量分/失败规则 + 血缘影响摘要（soft-fail）
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
