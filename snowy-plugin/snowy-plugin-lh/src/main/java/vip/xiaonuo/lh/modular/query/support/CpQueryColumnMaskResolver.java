package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.entity.SecMaskPolicyProj;
import vip.xiaonuo.lh.modular.sec.mapper.SecAuthGrantMapper;
import vip.xiaonuo.lh.modular.sec.mapper.SecMaskPolicyProjMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 即席结果脱敏列：只认 Grav/Trino/门户策略投影，不再用列名启发式当引擎 mask。
 * <p>优先级：引擎回传 → {@code sec_mask_policy_proj} → grant.column_mask → Grav 列属性（soft-fail）。
 * 皆无时 {@code maskDegraded=true}，{@code maskCols} 为空。</p>
 */
@Slf4j
@Component
public class CpQueryColumnMaskResolver {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join|update|into)\\s+((?:[`\"\\[]?[\\w$]+[`\"\\]]?\\s*\\.\\s*){0,2}[`\"\\[]?[\\w$]+[`\"\\]]?)");
    private static final Set<String> NON_TABLE_TOKENS = Set.of(
            "select", "dual", "lateral", "unnest", "values");

    public static final String SOURCE_ENGINE = "engine";
    public static final String SOURCE_POLICY_PROJ = "policy_proj";
    public static final String SOURCE_GRANT = "grant";
    public static final String SOURCE_GRAV = "grav";
    public static final String SOURCE_NONE = "none";

    @Resource
    private SecMaskPolicyProjMapper maskPolicyMapper;
    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private LhProperties lhProperties;

    /**
     * 对结果列打标。{@code engineMaskCols} 为 Trino/代理显式回传的 mask 列（可空）。
     */
    public MaskResult resolve(List<String> resultColumns, String sql, String subjectId,
                              Collection<String> engineMaskCols) {
        List<String> columns = resultColumns == null ? List.of() : resultColumns;
        if (engineMaskCols != null && !engineMaskCols.isEmpty()) {
            List<String> hit = intersectPreserveOrder(columns, engineMaskCols);
            return MaskResult.of(hit, SOURCE_ENGINE, false,
                    hit.isEmpty() ? "引擎回传 mask 列与结果列无交集" : "引擎回传列级 mask");
        }

        List<TableRef> refs = extractTableRefs(sql);
        Set<String> policyCols = new LinkedHashSet<>();
        String source = SOURCE_NONE;

        List<CbGravAssetRef> assets = resolveGravRefs(refs);
        if (!assets.isEmpty()) {
            Set<String> fromProj = loadPolicyProjColumns(assets);
            if (!fromProj.isEmpty()) {
                policyCols.addAll(fromProj);
                source = SOURCE_POLICY_PROJ;
            }
            Set<String> fromGrant = loadGrantMaskColumns(subjectId, assets);
            if (!fromGrant.isEmpty()) {
                policyCols.addAll(fromGrant);
                if (SOURCE_NONE.equals(source)) {
                    source = SOURCE_GRANT;
                }
            }
            Set<String> fromGrav = loadGravMaskedColumns(assets);
            if (!fromGrav.isEmpty()) {
                policyCols.addAll(fromGrav);
                if (SOURCE_NONE.equals(source)) {
                    source = SOURCE_GRAV;
                }
            }
        }

        if (policyCols.isEmpty()) {
            return MaskResult.of(List.of(), SOURCE_NONE, true,
                    "无 Grav/Trino/门户列级 mask 策略；不按列名启发式打标（maskDegraded）");
        }
        List<String> hit = intersectPreserveOrder(columns, policyCols);
        return MaskResult.of(hit, source, false,
                "列级 mask 来自 " + source + (hit.isEmpty() ? "（与结果列无交集）" : ""));
    }

    /** 单表列浏览：是否对该列有引擎/投影 mask。 */
    public boolean isColumnMasked(String gravAssetId, String columnName) {
        if (StrUtil.hasBlank(gravAssetId, columnName)) {
            return false;
        }
        Long n = maskPolicyMapper.selectCount(new QueryWrapper<SecMaskPolicyProj>().lambda()
                .eq(SecMaskPolicyProj::getGravAssetId, gravAssetId)
                .eq(SecMaskPolicyProj::getColumnName, columnName)
                .eq(SecMaskPolicyProj::getStatus, "active")
                .eq(SecMaskPolicyProj::getDeleteFlag, NOT_DELETE));
        if (n != null && n > 0) {
            return true;
        }
        CbGravAssetRef ref = gravAssetRefMapper.selectById(gravAssetId);
        if (ref == null) {
            return false;
        }
        Set<String> grav = loadGravMaskedColumns(List.of(ref));
        for (String c : grav) {
            if (columnName.equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }

    /** 解析 grant.column_mask JSON / CSV（单测与投影共用）。 */
    public static Set<String> parseColumnMaskPayload(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (StrUtil.isBlank(raw)) {
            return out;
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("[")) {
                JSONArray arr = JSONUtil.parseArray(trimmed);
                for (int i = 0; i < arr.size(); i++) {
                    Object item = arr.get(i);
                    if (item instanceof String s) {
                        addCol(out, s);
                    } else if (item instanceof JSONObject jo) {
                        addCol(out, firstStr(jo, "column", "columnName", "name", "col"));
                    }
                }
                return out;
            }
            if (trimmed.startsWith("{")) {
                JSONObject jo = JSONUtil.parseObj(trimmed);
                Object cols = jo.get("columns");
                if (cols == null) {
                    cols = jo.get("maskCols");
                }
                if (cols instanceof JSONArray arr) {
                    for (int i = 0; i < arr.size(); i++) {
                        Object item = arr.get(i);
                        if (item instanceof String s) {
                            addCol(out, s);
                        } else if (item instanceof JSONObject one) {
                            addCol(out, firstStr(one, "column", "columnName", "name", "col"));
                        }
                    }
                } else {
                    addCol(out, firstStr(jo, "column", "columnName", "name"));
                }
                return out;
            }
        } catch (Exception e) {
            // fall through to CSV
        }
        for (String part : trimmed.split("[,;\\s]+")) {
            addCol(out, part);
        }
        return out;
    }

    static List<String> intersectPreserveOrder(List<String> columns, Collection<String> masked) {
        List<String> out = new ArrayList<>();
        if (columns == null || masked == null || masked.isEmpty()) {
            return out;
        }
        Set<String> lower = new LinkedHashSet<>();
        for (String m : masked) {
            if (StrUtil.isNotBlank(m)) {
                lower.add(m.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String c : columns) {
            if (c != null && lower.contains(c.toLowerCase(Locale.ROOT))) {
                out.add(c);
            }
        }
        return out;
    }

    static List<TableRef> extractTableRefs(String sql) {
        List<TableRef> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (StrUtil.isBlank(sql)) {
            return out;
        }
        String stripped = stripSqlComments(sql);
        Matcher m = TABLE_REF.matcher(stripped);
        while (m.find()) {
            String raw = m.group(1);
            if (StrUtil.isBlank(raw) || raw.trim().startsWith("(")) {
                continue;
            }
            TableRef ref = parseQualified(raw);
            if (ref == null || StrUtil.isBlank(ref.table)) {
                continue;
            }
            if (NON_TABLE_TOKENS.contains(ref.table.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String key = (StrUtil.blankToDefault(ref.catalog, "") + "."
                    + StrUtil.blankToDefault(ref.schema, "") + "." + ref.table).toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                out.add(ref);
            }
        }
        return out;
    }

    static TableRef parseQualified(String raw) {
        String cleaned = raw.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
        cleaned = cleaned.replaceAll("\\s+", "");
        String[] parts = cleaned.split("\\.");
        if (parts.length == 0) {
            return null;
        }
        if (parts.length == 1) {
            return new TableRef(null, null, parts[0]);
        }
        if (parts.length == 2) {
            return new TableRef(null, parts[0], parts[1]);
        }
        return new TableRef(parts[parts.length - 3], parts[parts.length - 2], parts[parts.length - 1]);
    }

    static String stripSqlComments(String sql) {
        String s = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        return s.replaceAll("(?m)--.*?$", " ");
    }

    private List<CbGravAssetRef> resolveGravRefs(List<TableRef> refs) {
        List<CbGravAssetRef> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (refs == null) {
            return out;
        }
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

    private Set<String> loadPolicyProjColumns(List<CbGravAssetRef> assets) {
        Set<String> out = new LinkedHashSet<>();
        List<String> ids = assets.stream().map(CbGravAssetRef::getId).filter(StrUtil::isNotBlank).toList();
        if (ids.isEmpty()) {
            return out;
        }
        List<SecMaskPolicyProj> rows = maskPolicyMapper.selectList(new QueryWrapper<SecMaskPolicyProj>().lambda()
                .in(SecMaskPolicyProj::getGravAssetId, ids)
                .eq(SecMaskPolicyProj::getStatus, "active")
                .eq(SecMaskPolicyProj::getDeleteFlag, NOT_DELETE));
        for (SecMaskPolicyProj row : rows) {
            addCol(out, row.getColumnName());
        }
        return out;
    }

    private Set<String> loadGrantMaskColumns(String subjectId, List<CbGravAssetRef> assets) {
        Set<String> out = new LinkedHashSet<>();
        if (StrUtil.isBlank(subjectId) || assets.isEmpty()) {
            return out;
        }
        List<String> gravIds = assets.stream().map(CbGravAssetRef::getId).filter(StrUtil::isNotBlank).toList();
        if (gravIds.isEmpty()) {
            return out;
        }
        List<SecAuthGrant> grants = grantMapper.selectList(new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getSubjectId, subjectId)
                .eq(SecAuthGrant::getStatus, "active")
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .in(SecAuthGrant::getGravAssetId, gravIds)
                .isNotNull(SecAuthGrant::getColumnMask));
        for (SecAuthGrant g : grants) {
            out.addAll(parseColumnMaskPayload(g.getColumnMask()));
        }
        return out;
    }

    private Set<String> loadGravMaskedColumns(List<CbGravAssetRef> assets) {
        Set<String> out = new LinkedHashSet<>();
        String metalakeDefault = lhProperties.getGravitino() == null
                ? "lakehouse"
                : StrUtil.blankToDefault(lhProperties.getGravitino().getMetalake(), "lakehouse");
        for (CbGravAssetRef ref : assets) {
            if (ref == null || StrUtil.hasBlank(ref.getGravCatalog(), ref.getGravSchema(), ref.getGravTable())) {
                continue;
            }
            String metalake = StrUtil.blankToDefault(ref.getGravMetalake(), metalakeDefault);
            try {
                GravitinoClient.GravTable gt = gravitinoClient.loadTable(
                        metalake, ref.getGravCatalog(), ref.getGravSchema(), ref.getGravTable());
                if (gt == null || gt.columns == null) {
                    continue;
                }
                for (GravitinoClient.GravColumn col : gt.columns) {
                    if (col != null && col.masked && StrUtil.isNotBlank(col.name)) {
                        out.add(col.name);
                    }
                }
            } catch (Exception e) {
                log.debug("Grav 列 mask 探测失败 {}.{}: {}", ref.getGravSchema(), ref.getGravTable(), e.getMessage());
            }
        }
        return out;
    }

    private static void addCol(Set<String> out, String name) {
        if (StrUtil.isNotBlank(name)) {
            out.add(name.trim());
        }
    }

    private static String firstStr(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.getStr(k);
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    /** 从 Trino exec 结果抽取引擎回传的 mask 列（若有）。 */
    @SuppressWarnings("unchecked")
    public static List<String> engineMaskColsFromExec(Map<String, Object> exec) {
        if (exec == null) {
            return null;
        }
        Object raw = exec.get("maskCols");
        if (raw == null) {
            raw = exec.get("maskedColumns");
        }
        if (raw instanceof Collection<?> col) {
            List<String> out = new ArrayList<>();
            for (Object o : col) {
                if (o != null && StrUtil.isNotBlank(String.valueOf(o))) {
                    out.add(String.valueOf(o).trim());
                }
            }
            return out.isEmpty() ? null : out;
        }
        if (raw instanceof String s && StrUtil.isNotBlank(s)) {
            return new ArrayList<>(parseColumnMaskPayload(s));
        }
        return null;
    }

    public static final class MaskResult {
        public final List<String> maskCols;
        public final String maskSource;
        public final boolean maskDegraded;
        public final String maskMessage;

        private MaskResult(List<String> maskCols, String maskSource, boolean maskDegraded, String maskMessage) {
            this.maskCols = maskCols == null ? List.of() : List.copyOf(maskCols);
            this.maskSource = maskSource;
            this.maskDegraded = maskDegraded;
            this.maskMessage = maskMessage;
        }

        public static MaskResult of(List<String> cols, String source, boolean degraded, String message) {
            return new MaskResult(cols, source, degraded, message);
        }
    }

    static final class TableRef {
        final String catalog;
        final String schema;
        final String table;

        TableRef(String catalog, String schema, String table) {
            this.catalog = catalog;
            this.schema = schema;
            this.table = table;
        }
    }
}
