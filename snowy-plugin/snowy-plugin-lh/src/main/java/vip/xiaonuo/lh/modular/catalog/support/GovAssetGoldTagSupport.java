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
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 黄金标签对账：OM tag SoT ↔ 门户 {@code gov_asset.is_gold}。
 * <p>默认识别 {@code Tier.Gold}；可通过配置追加等价 FQN。</p>
 *
 * @author lakehouse
 * @date 2026/3/19
 */
public final class GovAssetGoldTagSupport {

    public static final String DEFAULT_GOLD_TAG = "Tier.Gold";

    private GovAssetGoldTagSupport() {
    }

    public static List<String> normalizeGoldTagFqns(Collection<String> configured) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(DEFAULT_GOLD_TAG);
        if (configured != null) {
            for (String s : configured) {
                if (StrUtil.isNotBlank(s)) {
                    set.add(s.trim());
                }
            }
        }
        return List.copyOf(set);
    }

    /** 从 OM tags JSON（数组元素含 tagFQN）抽取 FQN 列表 */
    public static List<String> extractTagFqns(Object tagsNode) {
        List<String> out = new ArrayList<>();
        if (tagsNode == null) {
            return out;
        }
        if (tagsNode instanceof JSONArray arr) {
            for (Object o : arr) {
                String fqn = tagFqnOf(o);
                if (StrUtil.isNotBlank(fqn)) {
                    out.add(fqn);
                }
            }
            return out;
        }
        if (tagsNode instanceof Collection<?> col) {
            for (Object o : col) {
                String fqn = tagFqnOf(o);
                if (StrUtil.isNotBlank(fqn)) {
                    out.add(fqn);
                }
            }
            return out;
        }
        if (tagsNode instanceof String s && StrUtil.isNotBlank(s)) {
            out.add(s.trim());
        }
        return out;
    }

    public static boolean hasGoldTag(Collection<String> tagFqns, Collection<String> goldTagFqns) {
        Set<String> gold = new LinkedHashSet<>();
        for (String g : normalizeGoldTagFqns(goldTagFqns)) {
            gold.add(g.toLowerCase(Locale.ROOT));
        }
        if (tagFqns == null) {
            return false;
        }
        for (String t : tagFqns) {
            if (StrUtil.isBlank(t)) {
                continue;
            }
            if (gold.contains(t.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
            // 兼容仅写末段 Gold / GOLD
            String last = t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
            if ("gold".equalsIgnoreCase(last.trim()) && gold.stream().anyMatch(g -> g.endsWith(".gold"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对账结果：OM 有金标则门户应 is_gold=1；无金标则应为 0。
     * policy=om_wins：以 OM 为准回写门户。
     */
    public static Map<String, Object> reconcile(boolean portalIsGold, Collection<String> omTagFqns,
                                                 Collection<String> goldTagFqns) {
        List<String> goldFqns = normalizeGoldTagFqns(goldTagFqns);
        boolean omHasGold = hasGoldTag(omTagFqns, goldFqns);
        boolean aligned = portalIsGold == omHasGold;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("policy", "om_wins");
        r.put("goldTagFqns", goldFqns);
        r.put("portalIsGold", portalIsGold);
        r.put("omHasGold", omHasGold);
        r.put("aligned", aligned);
        r.put("desiredIsGold", omHasGold ? 1 : 0);
        if (!aligned) {
            r.put("action", omHasGold ? "promote_portal" : "delist_portal");
            r.put("hint", omHasGold
                    ? "OM 含黄金标签，门户 is_gold 将对齐为 1"
                    : "OM 无黄金标签，门户 is_gold 将对齐为 0");
        }
        return r;
    }

    /** 确保 tags 列表含/不含黄金标（写 OM 时用） */
    public static List<String> applyGoldFlag(List<String> tags, boolean wantGold, Collection<String> goldTagFqns) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (tags != null) {
            for (String t : tags) {
                if (StrUtil.isNotBlank(t)) {
                    set.add(t.trim());
                }
            }
        }
        List<String> gold = normalizeGoldTagFqns(goldTagFqns);
        String primary = gold.get(0);
        if (wantGold) {
            if (!hasGoldTag(set, gold)) {
                set.add(primary);
            }
        } else {
            set.removeIf(t -> hasGoldTag(List.of(t), gold));
        }
        return new ArrayList<>(set);
    }

    private static String tagFqnOf(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String s) {
            return s.trim();
        }
        if (o instanceof JSONObject jo) {
            return firstStr(jo, "tagFQN", "fullyQualifiedName", "name");
        }
        if (o instanceof Map<?, ?> m) {
            Object v = m.get("tagFQN");
            if (v == null) {
                v = m.get("fullyQualifiedName");
            }
            if (v == null) {
                v = m.get("name");
            }
            return v == null ? null : String.valueOf(v).trim();
        }
        return String.valueOf(o).trim();
    }

    private static String firstStr(JSONObject jo, String... keys) {
        for (String k : keys) {
            String v = jo.getStr(k);
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
