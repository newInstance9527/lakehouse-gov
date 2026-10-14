package vip.xiaonuo.lh.modular.sec.support;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.lh.modular.catalog.support.GovAssetGoldTagSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OM 敏感标签 → 脱敏算法 / 密级映射（门户投影用，不写 Grav mask）。
 */
public final class SecOmMaskTagSupport {

    private static final Pattern PHONE = Pattern.compile("(?i)(mobile|phone|tel|cell)");
    private static final Pattern ID = Pattern.compile("(?i)(id_?card|idcard|cert_?no|身份证|证件)");
    private static final Pattern EMAIL = Pattern.compile("(?i)(email|e_?mail|mail)");
    private static final Pattern BANK = Pattern.compile("(?i)(bank|card_?no|账号|卡号)");

    private SecOmMaskTagSupport() {
    }

    public static List<String> normalizePrefixes(String csv) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (StrUtil.isBlank(csv)) {
            return List.of("PII", "Sensitive");
        }
        for (String p : csv.split("[,;\\s]+")) {
            if (StrUtil.isNotBlank(p)) {
                set.add(p.trim());
            }
        }
        return List.copyOf(set);
    }

    public static boolean isSensitiveTag(String tagFqn, Collection<String> prefixes) {
        if (StrUtil.isBlank(tagFqn) || prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        String t = tagFqn.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        for (String p : prefixes) {
            if (StrUtil.isBlank(p)) {
                continue;
            }
            String pref = p.trim();
            if (t.equalsIgnoreCase(pref) || t.regionMatches(true, 0, pref, 0, pref.length())) {
                return true;
            }
            // 中文密级可能出现在 FQN 任意段
            if (pref.length() <= 4 && lower.contains(pref.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static String firstSensitiveTag(Collection<String> tagFqns, Collection<String> prefixes) {
        if (tagFqns == null) {
            return null;
        }
        for (String t : tagFqns) {
            if (isSensitiveTag(t, prefixes)) {
                return t;
            }
        }
        return null;
    }

    public static String resolveMaskAlgo(String columnName, String tagFqn) {
        String col = StrUtil.blankToDefault(columnName, "");
        String tag = StrUtil.blankToDefault(tagFqn, "").toLowerCase(Locale.ROOT);
        if (PHONE.matcher(col).find() || tag.contains("phone") || tag.contains("mobile")) {
            return "mask_phone";
        }
        if (ID.matcher(col).find() || tag.contains("idcard") || tag.contains("ssn")) {
            return "mask_id";
        }
        if (EMAIL.matcher(col).find() || tag.contains("email")) {
            return "mask_email";
        }
        if (BANK.matcher(col).find() || tag.contains("bank") || tag.contains("payment")) {
            return "mask_bank";
        }
        return "redact";
    }

    public static String resolveSensitivity(String tagFqn) {
        String t = StrUtil.blankToDefault(tagFqn, "").toLowerCase(Locale.ROOT);
        if (t.contains("机密") || t.contains("secret") || t.contains("confidential")) {
            return "机密";
        }
        if (t.contains("秘密") || t.contains("restricted")) {
            return "秘密";
        }
        if (t.contains("敏感") || t.contains("sensitive") || t.contains("pii")) {
            return "敏感";
        }
        return "sensitive";
    }

    public static List<String> extractTagFqns(Object tagsNode) {
        return GovAssetGoldTagSupport.extractTagFqns(tagsNode);
    }

    /** 行过滤预填模板（占位符由申请人改写；不自动注入用户身份）。 */
    public static List<java.util.Map<String, String>> rowFilterTemplates(String assetSensitivity) {
        List<java.util.Map<String, String>> out = new ArrayList<>();
        out.add(tpl("region_east", "限定华东", "region = '华东'"));
        out.add(tpl("org_eq", "本机构（改 org_id）", "org_id = 'YOUR_ORG'"));
        out.add(tpl("dt_30d", "近 30 天分区", "dt >= date_format(date_add('day', -30, current_date), '%Y-%m-%d')"));
        String sens = StrUtil.blankToDefault(assetSensitivity, "");
        if (sens.contains("机密") || sens.contains("秘密") || "secret".equalsIgnoreCase(sens)) {
            out.add(0, tpl("strict_owner_dept", "机密默认：本部门（改 dept_id）", "dept_id = 'YOUR_DEPT'"));
        }
        return out;
    }

    private static java.util.Map<String, String> tpl(String id, String label, String predicate) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("predicate", predicate);
        return m;
    }

    public static Set<String> splitCsv(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (StrUtil.isBlank(raw)) {
            return out;
        }
        for (String p : raw.split("[,;\\s]+")) {
            if (StrUtil.isNotBlank(p)) {
                out.add(p.trim());
            }
        }
        return out;
    }

    public static List<String> defaultPrefixes() {
        return Arrays.asList(
                "PII", "PersonalData", "Sensitive", "Tier.Confidential",
                "Classification.Sensitive", "敏感", "机密", "隐私");
    }
}
