package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.query.support.CpQueryColumnMaskResolver.TableRef;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.mapper.SecAuthGrantMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 即席行级策略：执行前按 {@code sec_auth_grant.row_filter} 对 FROM/JOIN 表引用注入强制谓词。
 * <p>Gravitino privilege.condition 仅为 ALLOW/DENY，不承载行过滤（见 {@code GravitinoClient}）；
 * 本层是门户强制生效点。复杂 SQL / Grav 原生 RLS 仍为残留。</p>
 */
@Slf4j
@Component
public class CpQueryRowFilterInjector {

    private static final String NOT_DELETE = "NOT_DELETE";
    public static final String SOURCE_GRANT = "grant";
    public static final String SOURCE_NONE = "none";

    private static final Pattern FROM_JOIN_TABLE = Pattern.compile(
            "(?i)(\\b(?:from|join)\\s+)"
                    + "((?:[`\"\\[]?[\\w$]+[`\"\\]]?\\s*\\.\\s*){0,2}[`\"\\[]?[\\w$]+[`\"\\]]?)");

    private static final Pattern TRAILING_ALIAS = Pattern.compile(
            "(?i)^\\s+(?:AS\\s+)?([`\"\\[]?[\\w$]+[`\"\\]]?)");

    private static final Set<String> NOT_ALIAS = Set.of(
            "on", "where", "join", "left", "right", "inner", "outer", "full", "cross",
            "group", "order", "limit", "union", "except", "intersect", "having", "as",
            "lateral", "natural", "using", "into", "values", "select", "with", "set",
            "fetch", "offset", "window", "qualify", "tablesample");

    private static final Pattern FORBIDDEN_PRED = Pattern.compile(
            "(?i)(;|--|/\\*|\\*/|\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|"
                    + "union|into|execute|exec|call|merge|replace|copy)\\b)");

    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;

    /**
     * 对 SELECT/WITH/EXPLAIN SELECT 注入行谓词；SHOW/DESCRIBE 等跳过。
     */
    public InjectResult inject(String sql, String subjectId) {
        String original = StrUtil.blankToDefault(sql, "");
        if (StrUtil.isBlank(original)) {
            return InjectResult.skipped(original, "SQL 为空");
        }
        if (!isSelectLike(original)) {
            return InjectResult.skipped(original, "非 SELECT 类语句不注入行级谓词");
        }

        List<TableRef> refs = CpQueryColumnMaskResolver.extractTableRefs(original);
        Map<String, TableFilter> filters = loadGrantFilters(subjectId, refs);
        if (filters.isEmpty()) {
            boolean hasTables = !refs.isEmpty();
            return InjectResult.degraded(original, hasTables,
                    hasTables
                            ? "查询表无生效的 sec_auth_grant.row_filter；未注入强制谓词（rowFilterDegraded）"
                            : "未解析到表引用；未注入行级谓词");
        }

        String rewritten = original;
        List<Map<String, Object>> applied = new ArrayList<>();
        int wrapSeq = 0;
        for (TableFilter tf : filters.values()) {
            WrapOutcome wo = wrapMatchingTableRefs(rewritten, tf.ref, tf.predicate, wrapSeq);
            rewritten = wo.sql;
            wrapSeq = wo.nextSeq;
            if (wo.wrapCount > 0) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("table", fqn(tf.ref));
                one.put("predicate", tf.predicate);
                one.put("source", SOURCE_GRANT);
                one.put("wrapCount", wo.wrapCount);
                if (StrUtil.isNotBlank(tf.grantId)) {
                    one.put("grantId", tf.grantId);
                }
                applied.add(one);
            }
        }

        if (applied.isEmpty()) {
            return InjectResult.policyFailed(original,
                    "已有 row_filter 策略但未能改写 SQL 表引用（复杂语法残留）");
        }
        return InjectResult.applied(original, rewritten, applied, SOURCE_GRANT,
                "行级谓词来自 sec_auth_grant.row_filter，已对 FROM/JOIN 表引用强制包裹");
    }

    static boolean isSelectLike(String sql) {
        String s = CpQueryColumnMaskResolver.stripSqlComments(sql).trim();
        if (s.isEmpty()) {
            return false;
        }
        String head = s.length() > 16 ? s.substring(0, 16) : s;
        String lower = head.toLowerCase(Locale.ROOT);
        if (lower.startsWith("show") || lower.startsWith("describe") || lower.startsWith("desc ")
                || lower.startsWith("use ") || lower.startsWith("set ")) {
            return false;
        }
        if (lower.startsWith("explain")) {
            String rest = s.substring(7).trim().toLowerCase(Locale.ROOT);
            return rest.startsWith("select") || rest.startsWith("with")
                    || rest.startsWith("analyze select") || rest.startsWith("analyze with");
        }
        return lower.startsWith("select") || lower.startsWith("with");
    }

    /** 谓词安全闸：拒绝多语句/DDL/DML 关键词与注释。 */
    public static boolean isSafePredicate(String raw) {
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        String s = raw.trim();
        if (s.length() > 1024) {
            return false;
        }
        return !FORBIDDEN_PRED.matcher(s).find();
    }

    static String combinePredicates(List<String> parts) {
        List<String> safe = new ArrayList<>();
        for (String p : parts) {
            if (isSafePredicate(p)) {
                safe.add("(" + p.trim() + ")");
            }
        }
        if (safe.isEmpty()) {
            return null;
        }
        if (safe.size() == 1) {
            return safe.get(0);
        }
        return String.join(" AND ", safe);
    }

    private Map<String, TableFilter> loadGrantFilters(String subjectId, List<TableRef> refs) {
        Map<String, TableFilter> out = new LinkedHashMap<>();
        if (StrUtil.isBlank(subjectId) || refs == null || refs.isEmpty()) {
            return out;
        }
        List<CbGravAssetRef> assets = resolveGravRefs(refs);
        if (assets.isEmpty()) {
            return out;
        }
        List<String> gravIds = assets.stream().map(CbGravAssetRef::getId).filter(StrUtil::isNotBlank).toList();
        if (gravIds.isEmpty()) {
            return out;
        }
        Date now = new Date();
        List<SecAuthGrant> grants = grantMapper.selectList(new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getSubjectId, subjectId)
                .eq(SecAuthGrant::getStatus, "active")
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .in(SecAuthGrant::getGravAssetId, gravIds)
                .isNotNull(SecAuthGrant::getRowFilter)
                .ne(SecAuthGrant::getRowFilter, ""));
        Map<String, List<String>> byGrav = new LinkedHashMap<>();
        Map<String, String> firstGrantId = new LinkedHashMap<>();
        for (SecAuthGrant g : grants) {
            if (g == null || StrUtil.isBlank(g.getRowFilter()) || StrUtil.isBlank(g.getGravAssetId())) {
                continue;
            }
            if (g.getExpiresAt() != null && g.getExpiresAt().before(now)) {
                continue;
            }
            if (g.getEffectiveAt() != null && g.getEffectiveAt().after(now)) {
                continue;
            }
            if (!isSafePredicate(g.getRowFilter())) {
                log.warn("skip unsafe row_filter grantId={} pred={}", g.getId(),
                        StrUtil.maxLength(g.getRowFilter(), 80));
                continue;
            }
            byGrav.computeIfAbsent(g.getGravAssetId(), k -> new ArrayList<>()).add(g.getRowFilter().trim());
            firstGrantId.putIfAbsent(g.getGravAssetId(), g.getId());
        }
        Map<String, CbGravAssetRef> assetById = new LinkedHashMap<>();
        for (CbGravAssetRef a : assets) {
            if (a != null && StrUtil.isNotBlank(a.getId())) {
                assetById.put(a.getId(), a);
            }
        }
        for (Map.Entry<String, List<String>> e : byGrav.entrySet()) {
            CbGravAssetRef asset = assetById.get(e.getKey());
            if (asset == null) {
                continue;
            }
            String pred = combinePredicates(e.getValue());
            if (StrUtil.isBlank(pred)) {
                continue;
            }
            TableRef ref = new TableRef(asset.getGravCatalog(), asset.getGravSchema(), asset.getGravTable());
            String key = fqnKey(ref);
            out.put(key, new TableFilter(ref, pred, firstGrantId.get(e.getKey())));
        }
        // 无 Grav 指针时：仍尝试按 SQL 表名匹配 grant（resource 侧已有 row_filter 但 grav_asset_id 空则跳过）
        return out;
    }

    private List<CbGravAssetRef> resolveGravRefs(List<TableRef> refs) {
        List<CbGravAssetRef> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TableRef tr : refs) {
            if (tr == null || StrUtil.isBlank(tr.table)) {
                continue;
            }
            var qw = new QueryWrapper<CbGravAssetRef>().lambda()
                    .eq(CbGravAssetRef::getGravTable, tr.table)
                    .eq(CbGravAssetRef::getDeleteFlag, NOT_DELETE)
                    .ne(CbGravAssetRef::getStatus, "missing")
                    .ne(CbGravAssetRef::getStatus, "archived");
            if (StrUtil.isNotBlank(tr.schema)) {
                qw.eq(CbGravAssetRef::getGravSchema, tr.schema);
            }
            List<CbGravAssetRef> hit = gravAssetRefMapper.selectList(qw);
            boolean catalogExact = false;
            if (StrUtil.isNotBlank(tr.catalog)) {
                for (CbGravAssetRef ref : hit) {
                    if (ref != null && tr.catalog.equalsIgnoreCase(ref.getGravCatalog())) {
                        catalogExact = true;
                        break;
                    }
                }
            }
            for (CbGravAssetRef ref : hit) {
                if (ref == null || StrUtil.isBlank(ref.getId()) || !seen.add(ref.getId())) {
                    continue;
                }
                if (catalogExact && StrUtil.isNotBlank(tr.catalog)
                        && !tr.catalog.equalsIgnoreCase(ref.getGravCatalog())) {
                    continue;
                }
                out.add(ref);
            }
        }
        return out;
    }

    static WrapOutcome wrapMatchingTableRefs(String sql, TableRef target, String predicate, int startSeq) {
        if (StrUtil.hasBlank(sql, predicate) || target == null || StrUtil.isBlank(target.table)) {
            return new WrapOutcome(sql, 0, startSeq);
        }
        Matcher m = FROM_JOIN_TABLE.matcher(sql);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int wraps = 0;
        int seq = startSeq;
        while (m.find()) {
            String keyword = m.group(1);
            String rawTable = m.group(2);
            int replaceEnd = m.end();
            String rawAlias = null;
            Matcher aliasM = TRAILING_ALIAS.matcher(sql.substring(replaceEnd));
            if (aliasM.find()) {
                String cand = aliasM.group(1);
                if (StrUtil.isNotBlank(normalizeAlias(cand))) {
                    rawAlias = cand;
                    replaceEnd = replaceEnd + aliasM.end();
                }
            }
            TableRef found = CpQueryColumnMaskResolver.parseQualified(rawTable);
            if (!sameTable(found, target)) {
                continue;
            }
            String alias = normalizeAlias(rawAlias);
            if (StrUtil.isBlank(alias)) {
                alias = "__lh_rf" + (seq++);
            }
            String qualified = quoteQualified(target);
            String replacement = keyword + "(SELECT * FROM " + qualified + " WHERE " + predicate + ") AS " + alias;
            sb.append(sql, last, m.start());
            sb.append(replacement);
            last = replaceEnd;
            wraps++;
        }
        if (wraps == 0) {
            return new WrapOutcome(sql, 0, startSeq);
        }
        sb.append(sql, last, sql.length());
        return new WrapOutcome(sb.toString(), wraps, seq);
    }

    private static boolean sameTable(TableRef a, TableRef b) {
        if (a == null || b == null || StrUtil.isBlank(a.table) || StrUtil.isBlank(b.table)) {
            return false;
        }
        if (!a.table.equalsIgnoreCase(b.table)) {
            return false;
        }
        if (StrUtil.isNotBlank(a.schema) && StrUtil.isNotBlank(b.schema)
                && !a.schema.equalsIgnoreCase(b.schema)) {
            return false;
        }
        if (StrUtil.isNotBlank(a.catalog) && StrUtil.isNotBlank(b.catalog)
                && !a.catalog.equalsIgnoreCase(b.catalog)) {
            return false;
        }
        return true;
    }

    private static String normalizeAlias(String rawAlias) {
        if (StrUtil.isBlank(rawAlias)) {
            return null;
        }
        String cleaned = rawAlias.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").trim();
        if (cleaned.isEmpty() || NOT_ALIAS.contains(cleaned.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return cleaned;
    }

    private static String quoteQualified(TableRef ref) {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(ref.catalog)) {
            sb.append('"').append(ref.catalog.replace("\"", "")).append('"').append('.');
        }
        if (StrUtil.isNotBlank(ref.schema)) {
            sb.append('"').append(ref.schema.replace("\"", "")).append('"').append('.');
        }
        sb.append('"').append(ref.table.replace("\"", "")).append('"');
        return sb.toString();
    }

    static String fqn(TableRef ref) {
        if (ref == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (StrUtil.isNotBlank(ref.catalog)) {
            parts.add(ref.catalog);
        }
        if (StrUtil.isNotBlank(ref.schema)) {
            parts.add(ref.schema);
        }
        if (StrUtil.isNotBlank(ref.table)) {
            parts.add(ref.table);
        }
        return String.join(".", parts);
    }

    private static String fqnKey(TableRef ref) {
        return fqn(ref).toLowerCase(Locale.ROOT);
    }

    private static final class TableFilter {
        final TableRef ref;
        final String predicate;
        final String grantId;

        TableFilter(TableRef ref, String predicate, String grantId) {
            this.ref = ref;
            this.predicate = predicate;
            this.grantId = grantId;
        }
    }

    static final class WrapOutcome {
        final String sql;
        final int wrapCount;
        final int nextSeq;

        WrapOutcome(String sql, int wrapCount, int nextSeq) {
            this.sql = sql;
            this.wrapCount = wrapCount;
            this.nextSeq = nextSeq;
        }
    }

    public static final class InjectResult {
        public final String originalSql;
        public final String sql;
        public final boolean applied;
        public final boolean degraded;
        /** 有策略但未能强制注入（应硬失败） */
        public final boolean policyFailed;
        public final String source;
        public final String message;
        public final List<Map<String, Object>> predicates;

        private InjectResult(String originalSql, String sql, boolean applied, boolean degraded,
                             boolean policyFailed, String source, String message,
                             List<Map<String, Object>> predicates) {
            this.originalSql = originalSql;
            this.sql = sql;
            this.applied = applied;
            this.degraded = degraded;
            this.policyFailed = policyFailed;
            this.source = source;
            this.message = message;
            this.predicates = predicates == null ? List.of() : List.copyOf(predicates);
        }

        public static InjectResult skipped(String sql, String message) {
            return new InjectResult(sql, sql, false, false, false, SOURCE_NONE, message, List.of());
        }

        public static InjectResult degraded(String sql, boolean hasTables, String message) {
            return new InjectResult(sql, sql, false, hasTables, false, SOURCE_NONE, message, List.of());
        }

        public static InjectResult policyFailed(String sql, String message) {
            return new InjectResult(sql, sql, false, true, true, SOURCE_NONE, message, List.of());
        }

        public static InjectResult applied(String original, String rewritten,
                                           List<Map<String, Object>> predicates,
                                           String source, String message) {
            return new InjectResult(original, rewritten, true, false, false, source, message, predicates);
        }
    }
}
