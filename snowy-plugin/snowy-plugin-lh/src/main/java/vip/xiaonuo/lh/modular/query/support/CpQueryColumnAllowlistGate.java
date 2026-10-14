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
import vip.xiaonuo.lh.modular.sec.service.impl.SecAuthGrantServiceImpl;

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
 * 即席 / DataAPI 列白名单硬闸：{@code sec_auth_grant.allowed_columns} 非空时拒绝 SELECT * 与越权列。
 */
@Slf4j
@Component
public class CpQueryColumnAllowlistGate {

    private static final String NOT_DELETE = "NOT_DELETE";

    /** SELECT 列表起始到 FROM/INTO 之间 */
    private static final Pattern SELECT_LIST = Pattern.compile(
            "(?is)\\bselect\\b\\s+(distinct\\s+|all\\s+)?(.+?)\\s+\\b(?:from|into)\\b");

    private static final Pattern STAR = Pattern.compile("(?i)(^|[,\\s])\\*(?=\\s|,|$)");

    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;

    public GateResult check(String sql, String subjectId) {
        String original = StrUtil.blankToDefault(sql, "");
        if (StrUtil.isBlank(original) || StrUtil.isBlank(subjectId)) {
            return GateResult.pass(original, "无主体或 SQL，跳过列闸");
        }
        if (!CpQueryRowFilterInjector.isSelectLike(original)) {
            return GateResult.pass(original, "非 SELECT 类语句跳过列闸");
        }

        List<TableRef> refs = CpQueryColumnMaskResolver.extractTableRefs(original);
        Map<String, Allowlist> allowByTable = loadAllowlists(subjectId, refs);
        if (allowByTable.isEmpty()) {
            return GateResult.pass(original, "查询表无 allowed_columns，不限制列");
        }

        // 多表时取交集：任一表有白名单则整体受最严约束（并集拒绝：任一列须在所有白名单内）
        Set<String> intersection = null;
        List<String> grantIds = new ArrayList<>();
        List<Map<String, Object>> applied = new ArrayList<>();
        for (Allowlist al : allowByTable.values()) {
            if (al == null || al.columns.isEmpty()) {
                continue;
            }
            Set<String> lower = new LinkedHashSet<>();
            for (String c : al.columns) {
                lower.add(c.toLowerCase(Locale.ROOT));
            }
            if (intersection == null) {
                intersection = new LinkedHashSet<>(lower);
            } else {
                intersection.retainAll(lower);
            }
            if (StrUtil.isNotBlank(al.grantId)) {
                grantIds.add(al.grantId);
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("table", al.fqn);
            one.put("allowedColumns", new ArrayList<>(al.columns));
            one.put("grantId", al.grantId);
            applied.add(one);
        }
        if (intersection == null || intersection.isEmpty()) {
            return GateResult.denied(original, "列白名单交集为空，拒绝查询", applied, grantIds);
        }

        SelectProjection proj = parseSelectProjection(original);
        if (proj.star) {
            return GateResult.denied(original,
                    "授权含列白名单，禁止 SELECT *；请显式投影：" + String.join(",", intersection),
                    applied, grantIds);
        }
        if (proj.columns.isEmpty()) {
            return GateResult.denied(original, "未能解析 SELECT 投影列，拒绝查询（列白名单生效中）",
                    applied, grantIds);
        }
        List<String> denied = new ArrayList<>();
        for (String col : proj.columns) {
            if (!intersection.contains(col.toLowerCase(Locale.ROOT))) {
                denied.add(col);
            }
        }
        if (!denied.isEmpty()) {
            return GateResult.denied(original,
                    "列超出授权白名单：" + String.join(",", denied)
                            + "；允许：" + String.join(",", intersection),
                    applied, grantIds);
        }
        return GateResult.applied(original, applied, grantIds,
                "列白名单校验通过（" + intersection.size() + " 列）");
    }

    private Map<String, Allowlist> loadAllowlists(String subjectId, List<TableRef> refs) {
        Map<String, Allowlist> out = new LinkedHashMap<>();
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
                .eq(SecAuthGrant::getPrivilege, "SELECT")
                .in(SecAuthGrant::getGravAssetId, gravIds)
                .isNotNull(SecAuthGrant::getAllowedColumns)
                .ne(SecAuthGrant::getAllowedColumns, ""));
        Map<String, CbGravAssetRef> byId = new LinkedHashMap<>();
        for (CbGravAssetRef a : assets) {
            if (a != null && StrUtil.isNotBlank(a.getId())) {
                byId.put(a.getId(), a);
            }
        }
        for (SecAuthGrant g : grants) {
            if (g == null || StrUtil.isBlank(g.getGravAssetId())) {
                continue;
            }
            if (g.getExpiresAt() != null && g.getExpiresAt().before(now)) {
                continue;
            }
            if (g.getEffectiveAt() != null && g.getEffectiveAt().after(now)) {
                continue;
            }
            String norm = SecAuthGrantServiceImpl.normalizeAllowedColumns(g.getAllowedColumns());
            if (StrUtil.isBlank(norm)) {
                continue;
            }
            CbGravAssetRef asset = byId.get(g.getGravAssetId());
            if (asset == null) {
                continue;
            }
            Set<String> cols = new LinkedHashSet<>();
            for (String c : norm.split(",")) {
                if (StrUtil.isNotBlank(c)) {
                    cols.add(c.trim().toLowerCase(Locale.ROOT));
                }
            }
            String fqn = StrUtil.blankToDefault(asset.getGravCatalog(), "") + "."
                    + StrUtil.blankToDefault(asset.getGravSchema(), "") + "."
                    + asset.getGravTable();
            // 同表多 grant：取交集
            Allowlist existing = out.get(g.getGravAssetId());
            if (existing == null) {
                out.put(g.getGravAssetId(), new Allowlist(fqn, cols, g.getId()));
            } else {
                existing.columns.retainAll(cols);
            }
        }
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

    static SelectProjection parseSelectProjection(String sql) {
        SelectProjection p = new SelectProjection();
        String stripped = CpQueryColumnMaskResolver.stripSqlComments(sql);
        Matcher m = SELECT_LIST.matcher(stripped);
        if (!m.find()) {
            return p;
        }
        String list = m.group(2);
        if (StrUtil.isBlank(list)) {
            return p;
        }
        if (STAR.matcher(list).find() || list.trim().equals("*")
                || list.trim().matches("(?i)[\\w$\"`.]+\\.\\*")) {
            p.star = true;
            return p;
        }
        for (String part : splitSelectItems(list)) {
            String col = extractColumnName(part);
            if (StrUtil.isNotBlank(col) && !"*".equals(col)) {
                p.columns.add(col);
            } else if ("*".equals(col) || part.contains("*")) {
                p.star = true;
            }
        }
        return p;
    }

    static List<String> splitSelectItems(String list) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    /** 取表达式末列名：支持 alias、a.b.c、函数则跳过（无法判定则不加入白名单校验列）。 */
    static String extractColumnName(String expr) {
        if (StrUtil.isBlank(expr)) {
            return null;
        }
        String s = expr.trim();
        // 函数 / 子查询：含 ( 则跳过（不计入投影列，避免误杀）
        if (s.contains("(")) {
            return null;
        }
        // AS alias
        Matcher as = Pattern.compile("(?is)^(.+?)\\s+as\\s+([`\"\\[]?[\\w$]+[`\"\\]]?)$").matcher(s);
        if (as.find()) {
            s = as.group(1).trim();
        } else {
            // bare alias: col alias
            Matcher bare = Pattern.compile("(?is)^(.+?)\\s+([`\"\\[]?[\\w$]+[`\"\\]]?)$").matcher(s);
            if (bare.find() && !bare.group(1).contains(".")) {
                // keep left as column expr when simple
            }
            Matcher bare2 = Pattern.compile("(?is)^((?:[`\"\\[]?[\\w$]+[`\"\\]]?\\s*\\.\\s*)*[`\"\\[]?[\\w$]+[`\"\\]]?)\\s+[\\w$]+$")
                    .matcher(s);
            if (bare2.find()) {
                s = bare2.group(1).trim();
            }
        }
        s = s.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").trim();
        if (s.contains(".")) {
            String[] parts = s.split("\\.");
            s = parts[parts.length - 1];
        }
        if (!s.matches("[\\w$]+")) {
            return null;
        }
        return s;
    }

    private static final class Allowlist {
        final String fqn;
        final Set<String> columns;
        final String grantId;

        Allowlist(String fqn, Set<String> columns, String grantId) {
            this.fqn = fqn;
            this.columns = columns;
            this.grantId = grantId;
        }
    }

    static final class SelectProjection {
        boolean star;
        final List<String> columns = new ArrayList<>();
    }

    public static final class GateResult {
        public final boolean denied;
        public final boolean applied;
        public final String sql;
        public final String message;
        public final List<Map<String, Object>> allowlists;
        public final List<String> grantIds;

        private GateResult(boolean denied, boolean applied, String sql, String message,
                           List<Map<String, Object>> allowlists, List<String> grantIds) {
            this.denied = denied;
            this.applied = applied;
            this.sql = sql;
            this.message = message;
            this.allowlists = allowlists == null ? List.of() : allowlists;
            this.grantIds = grantIds == null ? List.of() : grantIds;
        }

        static GateResult pass(String sql, String message) {
            return new GateResult(false, false, sql, message, List.of(), List.of());
        }

        static GateResult applied(String sql, List<Map<String, Object>> allowlists,
                                  List<String> grantIds, String message) {
            return new GateResult(false, true, sql, message, allowlists, grantIds);
        }

        static GateResult denied(String sql, String message, List<Map<String, Object>> allowlists,
                                 List<String> grantIds) {
            return new GateResult(true, true, sql, message, allowlists, grantIds);
        }
    }
}
