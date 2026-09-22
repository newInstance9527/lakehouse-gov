package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricMaterialize;

/**
 * 指标执行路由：{@code prefer=hot} 且物化对账通过 + CK 可用 → ClickHouse；否则 soft-fail 回退 Trino。
 */
public final class MetricExecRouter {

    private MetricExecRouter() {
    }

    public static Decision decide(String prefer, boolean ckConfigured, GovMetricMaterialize mat) {
        String p = StrUtil.blankToDefault(prefer, "trino").trim().toLowerCase();
        if (!"hot".equals(p)) {
            return Decision.trino();
        }
        if (!ckConfigured) {
            return Decision.fallback("ClickHouse 未配置");
        }
        if (mat == null) {
            return Decision.fallback("无 clickhouse 物化登记");
        }
        if (!"clickhouse".equalsIgnoreCase(StrUtil.blankToDefault(mat.getEngine(), ""))) {
            return Decision.fallback("物化引擎非 clickhouse: " + mat.getEngine());
        }
        if (mat.getReconOk() == null || mat.getReconOk() != 1) {
            return Decision.fallback("物化对账未通过（recon_ok≠1）");
        }
        if (StrUtil.isBlank(mat.getTargetTable())) {
            return Decision.fallback("物化目标表为空");
        }
        if (!"active".equalsIgnoreCase(StrUtil.blankToDefault(mat.getStatus(), "active"))) {
            return Decision.fallback("物化登记非 active");
        }
        return Decision.hot(mat);
    }

    public record Decision(
            boolean useHot,
            String engine,
            String dialect,
            String fallback,
            String fallbackReason,
            GovMetricMaterialize materialize
    ) {
        public static Decision trino() {
            return new Decision(false, "trino", "trino", null, null, null);
        }

        public static Decision fallback(String reason) {
            return new Decision(false, "trino", "trino", "trino", reason, null);
        }

        public static Decision hot(GovMetricMaterialize mat) {
            return new Decision(true, "clickhouse", "clickhouse", null, null, mat);
        }
    }
}
