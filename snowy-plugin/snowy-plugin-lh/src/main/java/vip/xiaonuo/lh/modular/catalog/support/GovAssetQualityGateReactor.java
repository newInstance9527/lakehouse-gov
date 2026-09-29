/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.catalog.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.enums.GovAssetStatusEnum;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.plat.service.PlatOutboxService;
import vip.xiaonuo.lh.modular.quality.entity.GovDqGate;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRuleRun;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqGateMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleRunMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 质量 runs → 目录：阻断降级/摘金；通过后按门禁分恢复；写回 lastSync* + outbox；
 * OM Tier.Gold / 质量分摘要 soft-fail。
 */
@Slf4j
@Component
public class GovAssetQualityGateReactor {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String SYNC_BLOCKED = "quality_blocked";
    private static final String SYNC_BLOCKED_GOLD = "quality_blocked:gold_delisted";
    private static final String SYNC_OK_PREFIX = "quality_ok:";

    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private GovDqGateMapper gateMapper;
    @Resource
    private GovDqRuleMapper ruleMapper;
    @Resource
    private GovDqRuleRunMapper runMapper;
    @Resource
    private PlatOutboxService platOutboxService;
    @Resource
    private OpenMetadataClient openMetadataClient;

    /**
     * 任意质量 run 后联动目录（阻断降级 / 通过恢复 / 分数水位）。
     */
    public Map<String, Object> onRunResult(GovDqRule rule, GovDqRuleRun run) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("applied", false);
        if (rule == null || run == null) {
            return r;
        }
        if (Integer.valueOf(1).equals(run.getBlocked())) {
            return onBlockedRun(rule, run);
        }
        return onPassedOrWarnRun(rule, run);
    }

    /**
     * 规则 run 阻断时联动目录。
     *
     * @return 联动摘要（写入 addRun 响应）
     */
    public Map<String, Object> onBlockedRun(GovDqRule rule, GovDqRuleRun run) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("applied", false);
        r.put("action", "block");
        if (rule == null || run == null || !Integer.valueOf(1).equals(run.getBlocked())) {
            return r;
        }
        try {
            GovAsset asset = resolveAsset(rule);
            if (asset == null) {
                r.put("reason", "asset_not_found");
                return r;
            }
            if (!shouldBlock(rule, asset)) {
                r.put("reason", "gate_not_blocking");
                r.put("assetId", asset.getId());
                return r;
            }
            boolean degraded = false;
            boolean goldDelisted = false;
            Date now = new Date();
            if (!GovAssetStatusEnum.ARCHIVED.getValue().equalsIgnoreCase(asset.getStatus())
                    && !GovAssetStatusEnum.DEGRADED.getValue().equalsIgnoreCase(asset.getStatus())) {
                asset.setStatus(GovAssetStatusEnum.DEGRADED.getValue());
                degraded = true;
            }
            if (Integer.valueOf(1).equals(asset.getIsGold())) {
                asset.setIsGold(0);
                goldDelisted = true;
            }
            asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
            asset.setLastSyncAt(now);
            asset.setLastSyncStatus(goldDelisted ? SYNC_BLOCKED_GOLD : SYNC_BLOCKED);
            assetMapper.updateById(asset);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("assetId", asset.getId());
            payload.put("assetCode", asset.getAssetCode());
            payload.put("ruleId", rule.getId());
            payload.put("runId", run.getId());
            payload.put("degraded", degraded);
            payload.put("goldDelisted", goldDelisted);
            payload.put("tableName", rule.getTableName());
            payload.put("okPct", run.getOkPct());
            String eventId = platOutboxService.appendSoft(
                    "quality.gate.blocked",
                    "gov_asset",
                    asset.getId(),
                    payload,
                    Map.of("source", "GovAssetQualityGateReactor"));
            r.put("applied", true);
            r.put("assetId", asset.getId());
            r.put("degraded", degraded);
            r.put("goldDelisted", goldDelisted);
            r.put("eventId", eventId);
            r.put("score", avgScoreForAsset(asset, rule));
            Map<String, Object> om = softOmWriteback(asset, false, r.get("score") instanceof BigDecimal
                    ? (BigDecimal) r.get("score") : avgScoreForAsset(asset, rule));
            r.put("om", om);
            return r;
        } catch (Exception e) {
            log.warn("quality→catalog soft-fail rule={}: {}", rule.getId(), e.getMessage());
            r.put("degraded", true);
            r.put("message", e.getMessage());
            return r;
        }
    }

    /** 通过/告警失败：写回分数水位；若曾因质量阻断则按门禁分恢复。 */
    private Map<String, Object> onPassedOrWarnRun(GovDqRule rule, GovDqRuleRun run) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("applied", false);
        r.put("action", "writeback");
        try {
            GovAsset asset = resolveAsset(rule);
            if (asset == null) {
                r.put("reason", "asset_not_found");
                return r;
            }
            BigDecimal score = avgScoreForAsset(asset, rule);
            BigDecimal minScore = resolveMinScore(rule, asset);
            boolean pass = Integer.valueOf(1).equals(run.getPass());
            boolean recovered = false;
            boolean goldRestored = false;
            String prevSync = StrUtil.blankToDefault(asset.getLastSyncStatus(), "");
            boolean wasQualityBlocked = prevSync.startsWith(SYNC_BLOCKED);
            Date now = new Date();

            if (pass && score != null && score.compareTo(minScore) >= 0 && wasQualityBlocked
                    && GovAssetStatusEnum.DEGRADED.getValue().equalsIgnoreCase(asset.getStatus())) {
                asset.setStatus(GovAssetStatusEnum.ACTIVE.getValue());
                recovered = true;
                if (SYNC_BLOCKED_GOLD.equals(prevSync) || prevSync.contains("gold_delisted")) {
                    asset.setIsGold(1);
                    goldRestored = true;
                }
            }

            String syncStatus = SYNC_OK_PREFIX
                    + (score == null ? "na" : score.setScale(1, RoundingMode.HALF_UP).toPlainString());
            if (!pass) {
                syncStatus = "quality_warn:"
                        + (score == null ? "na" : score.setScale(1, RoundingMode.HALF_UP).toPlainString());
            }
            asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
            asset.setLastSyncAt(now);
            // 未恢复且仍是阻断水位时保留 blocked 标记，否则写 ok/warn
            if (!(wasQualityBlocked && !recovered)) {
                asset.setLastSyncStatus(syncStatus);
            }
            assetMapper.updateById(asset);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("assetId", asset.getId());
            payload.put("ruleId", rule.getId());
            payload.put("runId", run.getId());
            payload.put("score", score);
            payload.put("minScore", minScore);
            payload.put("pass", pass);
            payload.put("recovered", recovered);
            payload.put("goldRestored", goldRestored);
            payload.put("omFqn", asset.getOmFqn());
            String eventId = platOutboxService.appendSoft(
                    "quality.score.writeback",
                    "gov_asset",
                    asset.getId(),
                    payload,
                    Map.of("source", "GovAssetQualityGateReactor"));

            r.put("applied", true);
            r.put("assetId", asset.getId());
            r.put("score", score);
            r.put("minScore", minScore);
            r.put("recovered", recovered);
            r.put("goldRestored", goldRestored);
            r.put("eventId", eventId);
            r.put("hint", "分数按 runs 聚合读路径；本处写 lastSync* + outbox + OM soft-fail");
            boolean wantGold = Integer.valueOf(1).equals(asset.getIsGold()) || goldRestored;
            Map<String, Object> om = softOmWriteback(asset, wantGold, score);
            r.put("om", om);
            return r;
        } catch (Exception e) {
            log.warn("quality score writeback soft-fail rule={}: {}", rule.getId(), e.getMessage());
            r.put("message", e.getMessage());
            return r;
        }
    }

    /**
     * soft-fail：按门户 is_gold 对齐 OM Tier.Gold；description 中 upsert {@code [lh-dq] score=} 行。
     */
    private Map<String, Object> softOmWriteback(GovAsset asset, boolean wantGold, BigDecimal score) {
        Map<String, Object> om = new LinkedHashMap<>();
        om.put("ok", false);
        if (asset == null || StrUtil.isBlank(asset.getOmFqn())) {
            om.put("skipped", true);
            om.put("reason", "no_om_fqn");
            return om;
        }
        try {
            Map<String, Object> health = openMetadataClient.health();
            if (!"UP".equalsIgnoreCase(String.valueOf(health.get("status")))) {
                om.put("skipped", true);
                om.put("reason", "om_down");
                return om;
            }
            var table = openMetadataClient.getTableByFqn(asset.getOmFqn().trim());
            if (table == null) {
                om.put("skipped", true);
                om.put("reason", "table_not_found");
                return om;
            }
            List<String> tags = GovAssetGoldTagSupport.extractTagFqns(table.get("tags"));
            List<String> nextTags = GovAssetGoldTagSupport.applyGoldFlag(tags, wantGold, null);
            String desc = StrUtil.nullToEmpty(table.getStr("description"));
            String scoreLine = "[lh-dq] score="
                    + (score == null ? "na" : score.setScale(1, RoundingMode.HALF_UP).toPlainString())
                    + " gold=" + wantGold;
            String patchedDesc = upsertDqScoreLine(desc, scoreLine);
            Map<String, Object> patched = openMetadataClient.patchEntityMeta(
                    "table",
                    asset.getOmFqn().trim(),
                    patchedDesc,
                    null,
                    nextTags);
            om.putAll(patched);
            om.put("ok", !Boolean.TRUE.equals(patched.get("skipped")));
            om.put("wantGold", wantGold);
            om.put("scoreLine", scoreLine);
            return om;
        } catch (Exception e) {
            log.debug("OM quality writeback soft-fail asset={}: {}", asset.getId(), e.getMessage());
            om.put("degraded", true);
            om.put("message", e.getMessage());
            return om;
        }
    }

    static String upsertDqScoreLine(String description, String scoreLine) {
        String desc = StrUtil.nullToEmpty(description);
        String[] lines = desc.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        for (String line : lines) {
            if (line != null && line.trim().startsWith("[lh-dq]")) {
                if (!replaced) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(scoreLine);
                    replaced = true;
                }
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line == null ? "" : line);
        }
        if (!replaced) {
            if (sb.length() > 0 && !sb.toString().endsWith("\n")) {
                sb.append('\n');
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(scoreLine);
        }
        return sb.toString();
    }

    private BigDecimal resolveMinScore(GovDqRule rule, GovAsset asset) {
        String ws = StrUtil.blankToDefault(rule.getWs(), "default");
        List<GovDqGate> gates = gateMapper.selectList(new QueryWrapper<GovDqGate>().lambda()
                .eq(GovDqGate::getWs, ws)
                .eq(GovDqGate::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 80"));
        BigDecimal min = null;
        String table = StrUtil.blankToDefault(rule.getTableName(), "");
        String layer = StrUtil.blankToDefault(rule.getLayer(), "");
        for (GovDqGate g : gates) {
            boolean match = StrUtil.isNotBlank(asset.getId()) && asset.getId().equals(g.getAssetId());
            if (!match && StrUtil.isNotBlank(table) && table.equals(g.getTableName())) {
                match = true;
            }
            if (!match && StrUtil.isNotBlank(layer) && layer.equalsIgnoreCase(g.getLayer())
                    && StrUtil.isBlank(g.getTableName()) && StrUtil.isBlank(g.getAssetId())) {
                match = true;
            }
            if (!match || g.getMinScore() == null) {
                continue;
            }
            if (min == null || g.getMinScore().compareTo(min) > 0) {
                min = g.getMinScore();
            }
        }
        return min == null ? new BigDecimal("95.00") : min;
    }

    private BigDecimal avgScoreForAsset(GovAsset asset, GovDqRule hint) {
        String ws = asset == null ? "default" : StrUtil.blankToDefault(asset.getWs(), "default");
        String assetId = asset == null ? null : asset.getId();
        String table = hint == null ? null : hint.getTableName();
        QueryWrapper<GovDqRule> qw = new QueryWrapper<>();
        qw.lambda().eq(GovDqRule::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(ws), GovDqRule::getWs, ws);
        if (StrUtil.isNotBlank(assetId) && StrUtil.isNotBlank(table)) {
            qw.lambda().and(w -> w.eq(GovDqRule::getAssetId, assetId).or().eq(GovDqRule::getTableName, table));
        } else if (StrUtil.isNotBlank(assetId)) {
            qw.lambda().eq(GovDqRule::getAssetId, assetId);
        } else if (StrUtil.isNotBlank(table)) {
            qw.lambda().eq(GovDqRule::getTableName, table);
        } else if (hint != null && StrUtil.isNotBlank(hint.getId())) {
            qw.lambda().eq(GovDqRule::getId, hint.getId());
        } else {
            return null;
        }
        qw.last("LIMIT 80");
        List<GovDqRule> rules = ruleMapper.selectList(qw);
        if (rules.isEmpty() && hint != null) {
            rules = List.of(hint);
        }
        List<BigDecimal> scores = new ArrayList<>();
        for (GovDqRule rule : rules) {
            GovDqRuleRun latest = runMapper.selectOne(new QueryWrapper<GovDqRuleRun>().lambda()
                    .eq(GovDqRuleRun::getRuleId, rule.getId())
                    .orderByDesc(GovDqRuleRun::getRanAt)
                    .last("LIMIT 1"));
            if (latest == null) {
                continue;
            }
            if (latest.getOkPct() != null) {
                scores.add(latest.getOkPct());
            } else if (Integer.valueOf(1).equals(latest.getPass())) {
                scores.add(new BigDecimal("100"));
            } else {
                scores.add(BigDecimal.ZERO);
            }
        }
        if (scores.isEmpty()) {
            return null;
        }
        BigDecimal sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(scores.size()), 1, RoundingMode.HALF_UP);
    }

    private boolean shouldBlock(GovDqRule rule, GovAsset asset) {
        if ("block".equalsIgnoreCase(StrUtil.blankToDefault(rule.getSeverity(), ""))) {
            return true;
        }
        String ws = StrUtil.blankToDefault(rule.getWs(), "default");
        List<GovDqGate> gates;
        if (StrUtil.isNotBlank(rule.getTableName())) {
            gates = gateMapper.selectList(new QueryWrapper<GovDqGate>().lambda()
                    .eq(GovDqGate::getWs, ws)
                    .eq(GovDqGate::getDeleteFlag, NOT_DELETE)
                    .and(w -> w.eq(GovDqGate::getAssetId, asset.getId())
                            .or().eq(GovDqGate::getTableName, rule.getTableName())));
        } else {
            gates = gateMapper.selectList(new QueryWrapper<GovDqGate>().lambda()
                    .eq(GovDqGate::getWs, ws)
                    .eq(GovDqGate::getDeleteFlag, NOT_DELETE)
                    .eq(GovDqGate::getAssetId, asset.getId()));
        }
        if (gates.isEmpty()) {
            return true;
        }
        return gates.stream().anyMatch(g -> Integer.valueOf(1).equals(g.getBlockOnFail()));
    }

    private GovAsset resolveAsset(GovDqRule rule) {
        if (StrUtil.isNotBlank(rule.getAssetId())) {
            GovAsset byId = assetMapper.selectById(rule.getAssetId());
            if (byId != null && NOT_DELETE.equals(byId.getDeleteFlag())) {
                return byId;
            }
        }
        String table = StrUtil.blankToDefault(rule.getTableName(), "").trim();
        if (StrUtil.isBlank(table)) {
            return null;
        }
        String shortName = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
        String ws = StrUtil.blankToDefault(rule.getWs(), "default");
        List<GovAsset> hits = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getWs, ws)
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .and(w -> w.eq(GovAsset::getAssetCode, table)
                        .or().eq(GovAsset::getName, table)
                        .or().eq(GovAsset::getName, shortName)
                        .or().like(GovAsset::getOmFqn, shortName))
                .last("LIMIT 5"));
        if (hits.isEmpty()) {
            return null;
        }
        String low = table.toLowerCase(Locale.ROOT);
        for (GovAsset a : hits) {
            if (low.equalsIgnoreCase(a.getAssetCode()) || low.equalsIgnoreCase(a.getName())
                    || shortName.equalsIgnoreCase(a.getName())) {
                return a;
            }
        }
        return hits.get(0);
    }
}
