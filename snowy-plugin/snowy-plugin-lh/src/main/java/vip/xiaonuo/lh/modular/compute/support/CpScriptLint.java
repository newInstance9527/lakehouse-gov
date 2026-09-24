package vip.xiaonuo.lh.modular.compute.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 开发脚本静态检查：禁止写/引用 prod Catalog，缺过滤条件只告警。
 */
public final class CpScriptLint {

    private static final Pattern PROD_WRITE = Pattern.compile(
            "(?i)\\b(insert|update|delete|merge|create|drop|alter|truncate)\\b[\\s\\S]{0,160}\\bprod_[a-z0-9_]*");
    private static final Pattern PROD_REF = Pattern.compile("(?i)\\bprod_[a-z0-9_]+\\.");
    /** 无环境前缀的生产层写：INSERT INTO dwd_xxx / ods. / ads_ */
    private static final Pattern PROD_LAYER_WRITE = Pattern.compile(
            "(?i)\\b(insert\\s+into|merge\\s+into|create\\s+table|drop\\s+table|alter\\s+table|truncate\\s+table)\\s+"
                    + "(?!dev_|stg_|test_|pre_)(ods_|dwd_|dws_|ads_|ods\\.|dwd\\.|dws\\.|ads\\.)");
    private static final Pattern PROD_BUCKET = Pattern.compile(
            "(?i)s3a?://[^\\s'\"]*(warehouse|iceberg-prod|prod-warehouse)");
    private static final Pattern SELECT = Pattern.compile("(?i)\\bselect\\b");
    private static final Pattern FROM = Pattern.compile("(?i)\\bfrom\\b");
    private static final Pattern WHERE = Pattern.compile("(?i)\\bwhere\\b");

    private CpScriptLint() {
    }

    public static List<Map<String, String>> check(String sql) {
        return check(sql, "TEST", true);
    }

    /**
     * @param env TEST/PRE（开发态）；发布流水线勿用本方法拦 prod 写入
     * @param forbidProdLayer 禁止无环境前缀的生产层（ods_/dwd_/dws_/ads_）写入
     */
    public static List<Map<String, String>> check(String sql, String env, boolean forbidProdLayer) {
        List<Map<String, String>> out = new ArrayList<>();
        String text = sql == null ? "" : sql;
        if (StrUtil.isBlank(text)) {
            out.add(item("空脚本", "warn"));
            return out;
        }
        if (PROD_WRITE.matcher(text).find() || PROD_REF.matcher(text).find()) {
            out.add(item("禁止引用或写入 prod Catalog", "error"));
        }
        if (forbidProdLayer && PROD_LAYER_WRITE.matcher(text).find()) {
            out.add(item("开发态禁止写生产层（请用 dev_* / stg_* Catalog，生产只经发布门禁）", "error"));
        }
        if (PROD_BUCKET.matcher(text).find()) {
            String e = StrUtil.blankToDefault(env, "TEST").toUpperCase(Locale.ROOT);
            if (!"PROD".equals(e)) {
                out.add(item("开发/试跑禁止直写生产对象存储桶（warehouse/iceberg-prod）", "error"));
            }
        }
        if (SELECT.matcher(text).find() && FROM.matcher(text).find() && !WHERE.matcher(text).find()) {
            out.add(item("SELECT 缺少 WHERE，可能全表扫描", "warn"));
        }
        if (out.isEmpty()) {
            out.add(item("静态检查通过", "ok"));
        }
        return out;
    }

    public static boolean hasError(List<Map<String, String>> lint) {
        if (lint == null) {
            return false;
        }
        for (Map<String, String> item : lint) {
            if ("error".equalsIgnoreCase(item.get("tone"))) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeEngine(String engine) {
        String e = StrUtil.blankToDefault(engine, "spark").trim().toLowerCase(Locale.ROOT);
        if (!"spark".equals(e) && !"flink".equals(e) && !"trino".equals(e)) {
            throw new IllegalArgumentException("引擎仅支持 spark / flink / trino");
        }
        return e;
    }

    public static String normalizeEnv(String env) {
        String e = StrUtil.blankToDefault(env, "TEST").trim().toUpperCase(Locale.ROOT);
        if ("STG".equals(e)) {
            e = "PRE";
        }
        if ("PROD".equals(e) || "PRODUCTION".equals(e)) {
            throw new IllegalArgumentException("开发页禁止选择 PROD，生产只经发布门禁");
        }
        if (!"TEST".equals(e) && !"PRE".equals(e)) {
            throw new IllegalArgumentException("环境仅支持 TEST / PRE");
        }
        return e;
    }

    /** 发布单目标环境：dev / stg，prod 须门禁通过后由发布动作写入。 */
    public static String releaseEnv(String env) {
        String e = StrUtil.blankToDefault(env, "stg").trim().toLowerCase(Locale.ROOT);
        if ("test".equals(e) || "dev".equals(e)) {
            return "dev";
        }
        if ("pre".equals(e) || "stg".equals(e)) {
            return "stg";
        }
        if ("prod".equals(e)) {
            throw new IllegalArgumentException("不能直接创建 prod 发布单");
        }
        throw new IllegalArgumentException("发布环境仅支持 dev / stg");
    }

    public static String normalizePath(String folder, String name, String path) {
        String p = StrUtil.blankToDefault(path, "").replace('\\', '/').trim();
        if (StrUtil.isBlank(p)) {
            String folderName = StrUtil.blankToDefault(folder, "default").replace('\\', '/').trim();
            String file = StrUtil.blankToDefault(name, "untitled.sql").trim();
            if (!file.toLowerCase(Locale.ROOT).endsWith(".sql")) {
                file = file + ".sql";
            }
            p = "scripts/" + folderName + "/" + file;
        }
        if (!p.startsWith("scripts/")) {
            p = "scripts/" + p;
        }
        if (p.contains("..") || p.startsWith("/") || p.contains(":")) {
            throw new IllegalArgumentException("脚本路径不合法");
        }
        if (!p.toLowerCase(Locale.ROOT).endsWith(".sql")) {
            throw new IllegalArgumentException("脚本必须是 .sql 文件");
        }
        if (!p.matches("scripts/[A-Za-z0-9_./\\-]+\\.sql")) {
            throw new IllegalArgumentException("脚本路径含非法字符");
        }
        return p;
    }

    public static String folderOf(String path) {
        String rest = path.startsWith("scripts/") ? path.substring("scripts/".length()) : path;
        int slash = rest.lastIndexOf('/');
        if (slash <= 0) {
            return "default";
        }
        return rest.substring(0, slash);
    }

    public static String nameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static Map<String, String> item(String label, String tone) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("tone", tone);
        return m;
    }
}
