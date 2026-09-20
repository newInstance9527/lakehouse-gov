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
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.enums.GovAssetStatusEnum;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.plat.service.PlatOutboxService;
import vip.xiaonuo.lh.modular.quality.entity.GovDqGate;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRuleRun;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqGateMapper;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 质量门禁失败 → 资产 degraded / 黄金摘牌（门户侧；与调度约定同进程联动）。
 */
@Slf4j
@Component
public class GovAssetQualityGateReactor {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private GovDqGateMapper gateMapper;
    @Resource
    private PlatOutboxService platOutboxService;

    /**
     * 规则 run 阻断时联动目录。
     *
     * @return 联动摘要（写入 addRun 响应）
     */
    public Map<String, Object> onBlockedRun(GovDqRule rule, GovDqRuleRun run) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("applied", false);
        if (rule == null || run == null || !Integer.valueOf(1).equals(run.getBlocked())) {
            return r;
        }
        try {
            GovAsset asset = resolveAsset(rule);
            if (asset == null) {
                r.put("reason", "asset_not_found");
                return r;
            }
            // 门禁表配置了 blockOnFail=false 时，仅记录 run，不摘牌/降级
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
            if (degraded || goldDelisted) {
                asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
                asset.setLastSyncAt(now);
                asset.setLastSyncStatus("quality_blocked");
                assetMapper.updateById(asset);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("assetId", asset.getId());
            payload.put("assetCode", asset.getAssetCode());
            payload.put("ruleId", rule.getId());
            payload.put("runId", run.getId());
            payload.put("degraded", degraded);
            payload.put("goldDelisted", goldDelisted);
            payload.put("tableName", rule.getTableName());
            String eventId = platOutboxService.appendSoft(
                    "quality.gate.blocked",
                    "gov_asset",
                    asset.getId(),
                    payload,
                    Map.of("source", "GovAssetQualityGateReactor"));
            r.put("applied", degraded || goldDelisted);
            r.put("assetId", asset.getId());
            r.put("degraded", degraded);
            r.put("goldDelisted", goldDelisted);
            r.put("eventId", eventId);
            return r;
        } catch (Exception e) {
            log.warn("quality→catalog soft-fail rule={}: {}", rule.getId(), e.getMessage());
            r.put("degraded", true);
            r.put("message", e.getMessage());
            return r;
        }
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
            // 无显式门禁时：blocked run 仍联动（ETL/severity 已判定阻断）
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
        // 优先精确匹配 name / code
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
