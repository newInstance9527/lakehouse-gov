package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcPolicy;
import vip.xiaonuo.lh.modular.lifecycle.entity.GovLcTableStat;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcPolicyMapper;
import vip.xiaonuo.lh.modular.lifecycle.mapper.GovLcTableStatMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * J4 事件驱动采集：Iceberg commit / 表状态变更钩子，<strong>仅 L3 低频表</strong>。
 * <p>L1/L2 高频 CDC 禁止走本通道（会打成采集风暴）；日批仍覆盖全表。
 */
@Component
public class GovLcStorageEventCollect {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String WS_DEFAULT = "default";
    public static final String LEVEL_L3 = "L3";

    @Resource
    private LhProperties lhProperties;
    @Resource
    private GovLcPolicyMapper policyMapper;
    @Resource
    private GovLcTableStatMapper tableStatMapper;
    @Resource
    private GovLcStorageProfileCollector profileCollector;

    /**
     * @param ws       工作空间；空则 default
     * @param tableFqn 表 FQN
     * @param eventId  可选幂等键（仅回显）
     */
    public Map<String, Object> onCommit(String ws, String tableFqn, String eventId) {
        Map<String, Object> out = new LinkedHashMap<>();
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT).trim();
        String fqn = StrUtil.trim(tableFqn);
        out.put("ws", workspace);
        out.put("tableFqn", fqn);
        out.put("eventId", StrUtil.blankToDefault(eventId, null));
        out.put("channel", "on-commit");

        if (!eventCollectEnabled()) {
            out.put("accepted", false);
            out.put("skipped", true);
            out.put("reason", "event_collect_disabled");
            return out;
        }
        if (StrUtil.isBlank(fqn)) {
            out.put("accepted", false);
            out.put("skipped", true);
            out.put("reason", "table_fqn_required");
            return out;
        }

        Gate gate = resolveGate(workspace, fqn);
        out.put("compactLevel", gate.level);
        if (!gate.l3) {
            out.put("accepted", false);
            out.put("skipped", true);
            out.put("reason", "not_l3");
            out.put("note", "事件驱动采集仅允许 compact_level=L3；L1/L2 请走日批 profile_daily");
            return out;
        }

        int cooldownMin = cooldownMinutes();
        if (withinCooldown(workspace, fqn, cooldownMin)) {
            out.put("accepted", true);
            out.put("skipped", true);
            out.put("reason", "cooldown");
            out.put("cooldownMinutes", cooldownMin);
            out.put("note", "同表冷却期内跳过，避免 commit 风暴");
            return out;
        }

        Map<String, Object> profile = profileCollector.refresh(workspace, List.of(fqn));
        out.put("accepted", true);
        out.put("skipped", false);
        out.put("reason", "profiled");
        out.put("profile", profile);
        return out;
    }

    /** 纯判定：是否 L3（单测用）。 */
    public static boolean isL3(String compactLevel) {
        return LEVEL_L3.equalsIgnoreCase(StrUtil.blankToDefault(compactLevel, "").trim());
    }

    private Gate resolveGate(String ws, String tableFqn) {
        GovLcPolicy policy = policyMapper.selectOne(new QueryWrapper<GovLcPolicy>().lambda()
                .eq(GovLcPolicy::getWs, ws)
                .eq(GovLcPolicy::getTableFqn, tableFqn)
                .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (policy == null) {
            // 兼容：无策略时按表名再试一次（ws 不一致）
            policy = policyMapper.selectOne(new QueryWrapper<GovLcPolicy>().lambda()
                    .eq(GovLcPolicy::getTableFqn, tableFqn)
                    .eq(GovLcPolicy::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
        }
        String level = policy == null ? null : StrUtil.trim(policy.getCompactLevel());
        if (level != null) {
            level = level.toUpperCase(Locale.ROOT);
        }
        return new Gate(level, isL3(level));
    }

    private boolean withinCooldown(String ws, String tableFqn, int cooldownMin) {
        if (cooldownMin <= 0) {
            return false;
        }
        GovLcTableStat stat = tableStatMapper.selectOne(new QueryWrapper<GovLcTableStat>().lambda()
                .eq(GovLcTableStat::getWs, ws)
                .eq(GovLcTableStat::getTableFqn, tableFqn)
                .eq(GovLcTableStat::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (stat == null || stat.getCollectedAt() == null) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - stat.getCollectedAt().getTime();
        return elapsed < cooldownMin * 60_000L;
    }

    private boolean eventCollectEnabled() {
        return lhProperties.getLifecycle() == null
                || lhProperties.getLifecycle().isEventCollectEnabled();
    }

    private int cooldownMinutes() {
        if (lhProperties.getLifecycle() == null) {
            return 30;
        }
        return Math.max(0, lhProperties.getLifecycle().getEventCollectCooldownMinutes());
    }

    private record Gate(String level, boolean l3) {
    }
}
