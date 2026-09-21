package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 复合公式解析（对齐前端 parseMetricFormula）
 */
public final class GovMetricFormulaParser {

    private static final Pattern REF = Pattern.compile("\\b([ACM]-\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE = Pattern.compile("\\s+WHERE\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    private GovMetricFormulaParser() {
    }

    public static Result parse(String formula) {
        String raw = StrUtil.trim(formula);
        if (StrUtil.isBlank(raw)) {
            return Result.fail("公式为空");
        }
        if (raw.contains("·") || raw.contains("•") || raw.matches(".*按[天周月].*")) {
            return Result.fail("维度/时间窗请用独立字段，勿写入公式");
        }
        String expr = raw;
        String filter = "";
        Matcher whereHit = WHERE.matcher(raw);
        if (whereHit.find()) {
            filter = whereHit.group(1).trim();
            expr = raw.substring(0, whereHit.start()).trim();
        }
        if (!expr.matches("(?i)[\\sACM0-9+\\-*/()]+")) {
            return Result.fail("公式仅允许指标 ID 与 + - * / ( )");
        }
        Matcher m = REF.matcher(expr);
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        while (m.find()) {
            refs.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        if (refs.isEmpty()) {
            return Result.fail("公式中未找到指标 ID（如 A-0012）");
        }
        return Result.ok(expr.replaceAll("\\s+", " "), filter, new ArrayList<>(refs));
    }

    public record Result(boolean ok, String error, String expr, String filter, List<String> refs) {
        static Result ok(String expr, String filter, List<String> refs) {
            return new Result(true, null, expr, filter, refs);
        }

        static Result fail(String error) {
            return new Result(false, error, null, null, List.of());
        }
    }
}
