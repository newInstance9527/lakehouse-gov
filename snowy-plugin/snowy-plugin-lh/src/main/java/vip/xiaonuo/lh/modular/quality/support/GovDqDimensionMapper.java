package vip.xiaonuo.lh.modular.quality.support;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

/**
 * 规则类型 → DAMA 常用质量六维（组织级分数卡用）。
 */
public final class GovDqDimensionMapper {

    private GovDqDimensionMapper() {
    }

    public static String toDimension(String ruleType, String ruleCode) {
        String raw = StrUtil.blankToDefault(ruleType, ruleCode).trim().toLowerCase(Locale.ROOT);
        if (raw.contains("非空") || raw.contains("null") || raw.contains("not_null")
                || raw.contains("complete") || raw.contains("完整")) {
            return "completeness";
        }
        if (raw.contains("主键") || raw.contains("unique") || raw.contains("pk_")
                || raw.contains("唯一")) {
            return "uniqueness";
        }
        if (raw.contains("枚举") || raw.contains("enum") || raw.contains("码值")
                || raw.contains("范围") || raw.contains("range") || raw.contains("格式")
                || raw.contains("valid")) {
            return "validity";
        }
        if (raw.contains("一致") || raw.contains("consist") || raw.contains("参照")
                || raw.contains("referential") || raw.contains("fk")) {
            return "consistency";
        }
        if (raw.contains("时效") || raw.contains("及时") || raw.contains("time")
                || raw.contains("fresh") || raw.contains("延迟") || raw.contains("sla")
                || raw.contains("row_count") || raw.contains("行数")) {
            return "timeliness";
        }
        if (raw.contains("准确") || raw.contains("accur") || raw.contains("校准")) {
            return "accuracy";
        }
        return "other";
    }

    public static String labelZh(String dim) {
        return switch (StrUtil.blankToDefault(dim, "other")) {
            case "completeness" -> "完整性";
            case "uniqueness" -> "唯一性";
            case "validity" -> "有效性";
            case "consistency" -> "一致性";
            case "timeliness" -> "时效性";
            case "accuracy" -> "准确性";
            default -> "其他";
        };
    }
}
