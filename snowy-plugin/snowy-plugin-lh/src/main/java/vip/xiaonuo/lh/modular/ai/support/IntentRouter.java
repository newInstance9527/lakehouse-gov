package vip.xiaonuo.lh.modular.ai.support;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

/**
 * 意图路由：关键词规则 → nl2sql|gen_script|docqa|diagnose|explain|sql_opt
 */
public final class IntentRouter {

    private IntentRouter() {
    }

    /**
     * @param text  用户输入
     * @param scene 前端芯片显式 scene（可空）
     * @return 意图枚举
     */
    public static String route(String text, String scene) {
        if (StrUtil.isNotBlank(scene)) {
            String s = scene.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (s) {
                case "nl2sql", "write_sql", "sql" -> "nl2sql";
                case "gen_script", "write_script", "script" -> "gen_script";
                case "docqa", "manual" -> "docqa";
                case "diagnose" -> "diagnose";
                case "explain" -> "explain";
                case "sql_opt", "optimize", "sql-opt" -> "sql_opt";
                default -> classifyByText(text);
            };
        }
        return classifyByText(text);
    }

    private static String classifyByText(String text) {
        String t = StrUtil.nullToEmpty(text).toLowerCase(Locale.ROOT);
        if (containsAny(t, "flink", "脚本", "datastream", "作业模板")) {
            return "gen_script";
        }
        if (containsAny(t, "优化", "explain", "慢查询", "扫全表", "加分区")) {
            return "sql_opt";
        }
        if (containsAny(t, "诊断", "告警", "失败", "根因", "质量分")) {
            return "diagnose";
        }
        if (containsAny(t, "手册", "怎么用", "如何申请", "faq", "帮助")) {
            return "docqa";
        }
        if (containsAny(t, "解释", "是什么", "口径", "含义", "字段说明")) {
            return "explain";
        }
        if (containsAny(t, "sql", "查询", "select", "近", "天", "gmv", "指标", "统计", "汇总")) {
            return "nl2sql";
        }
        return "docqa";
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 场景路由键（模型管理 scene） */
    public static String modelScene(String intent) {
        return switch (StrUtil.nullToEmpty(intent)) {
            case "nl2sql", "sql_opt" -> "sql";
            case "gen_script" -> "script";
            case "diagnose" -> "diagnose";
            case "docqa", "explain" -> "manual";
            default -> "manual";
        };
    }
}
