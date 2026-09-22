package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.modular.catalog.entity.GovAssetSourceLink;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetSourceLinkMapper;
import vip.xiaonuo.lh.modular.datasource.result.LhMetaColumnVo;
import vip.xiaonuo.lh.modular.datasource.result.LhMetaObjectVo;
import vip.xiaonuo.lh.modular.sec.service.GravTableAccessService;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 构建 API 表级闸门：连接级之上，按资产源绑定 + 表读权限过滤 / 校验。
 * 列表用门户 SELECT 投影；trial/build 再走 Grav 探针。
 */
@Component
public class ApiBuildTableAccess {

    private static final String NOT_DELETE = "NOT_DELETE";

    /** 列名启发式敏感提示（与即席一致；非 Grav 列 ACL） */
    private static final Pattern MASK_HINT = Pattern.compile(
            "(mobile|phone|id_card|idcard|email|password|secret|token)", Pattern.CASE_INSENSITIVE);

    /** 轻量抽取 FROM/JOIN/UPDATE/INTO 后的表引用 */
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join|update|into)\\s+((?:[`\"\\[]?[\\w$]+[`\"\\]]?\\s*\\.\\s*){0,2}[`\"\\[]?[\\w$]+[`\"\\]]?)");

    private static final Set<String> NON_TABLE_TOKENS = Set.of(
            "select", "dual", "lateral", "unnest", "values");

    @Resource
    private GovAssetSourceLinkMapper sourceLinkMapper;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private GravTableAccessService gravTableAccessService;

    /**
     * 过滤表/视图列表：超管不过滤；否则仅保留已挂资产且 hasTableReadGrant 的对象。
     * 未挂目录的对象对非超管隐藏。
     */
    public List<LhMetaObjectVo> filterReadableObjects(String dsId, String schema, List<LhMetaObjectVo> raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? List.of() : raw;
        }
        if (LhLoginUsers.isSuperAdmin()) {
            return raw;
        }
        List<GovAssetSourceLink> links = loadLinks(dsId);
        if (links.isEmpty()) {
            return List.of();
        }
        List<LhMetaObjectVo> out = new ArrayList<>();
        for (LhMetaObjectVo vo : raw) {
            if (vo == null || StrUtil.isBlank(vo.getName())) {
                continue;
            }
            String assetId = resolveAssetId(links, schema, vo.getName());
            if (StrUtil.isNotBlank(assetId) && secAuthGrantService.hasTableReadGrant(assetId)) {
                out.add(vo);
            }
        }
        return out;
    }

    /** 列浏览前：须有资产绑定且表读权（超管跳过）。 */
    public void assertCanReadTableForMeta(String dsId, String schema, String table) {
        if (LhLoginUsers.isSuperAdmin()) {
            return;
        }
        String assetId = requireLinkedAssetId(dsId, schema, table);
        if (!secAuthGrantService.hasTableReadGrant(assetId)) {
            throw new CommonException("无表读权限：" + qualify(schema, table) + "（请先申请表级 SELECT）");
        }
    }

    /** 为列结果打敏感提示（不拦截）。 */
    public void markSensitiveColumns(List<LhMetaColumnVo> cols) {
        if (cols == null) {
            return;
        }
        for (LhMetaColumnVo col : cols) {
            if (col == null || StrUtil.isBlank(col.getName())) {
                continue;
            }
            if (MASK_HINT.matcher(col.getName()).find()) {
                col.setSensitive(true);
                col.setMaskedHint("敏感列名提示（启发式，非引擎列 ACL）");
            } else {
                col.setSensitive(false);
            }
        }
    }

    /**
     * trial/build：从 SQL 抽表，逐表 Grav canCurrentSelect。
     * GROOVY 无法可靠抽表则跳过表级；SQL 含 FROM/JOIN 却抽不出表则拒绝。
     */
    public void assertSqlTablesSelectable(String dsId, String engine, String sql) {
        if (LhLoginUsers.isSuperAdmin() || StrUtil.isBlank(dsId)) {
            return;
        }
        String eng = StrUtil.blankToDefault(engine, "SQL").trim().toUpperCase(Locale.ROOT);
        if ("GROOVY".equals(eng)) {
            return;
        }
        if (StrUtil.isBlank(sql)) {
            return;
        }
        String stripped = stripSqlComments(sql);
        List<TableRef> refs = extractTableRefs(stripped);
        boolean mentionsFrom = Pattern.compile("(?i)\\b(from|join|update|into)\\b").matcher(stripped).find();
        if (refs.isEmpty()) {
            if (mentionsFrom) {
                throw new CommonException("无法从 SQL 解析表名，已拒绝构建/试跑（请使用 schema.table 或先在左树选表）");
            }
            return;
        }
        List<GovAssetSourceLink> links = loadLinks(dsId);
        for (TableRef ref : refs) {
            String schema = StrUtil.blankToDefault(ref.schema, "");
            String table = ref.table;
            String assetId = resolveAssetId(links, schema, table);
            if (StrUtil.isBlank(assetId)) {
                throw new CommonException("表未入资产目录，拒绝构建/试跑：" + qualify(schema, table));
            }
            if (!gravTableAccessService.canCurrentSelect(assetId)) {
                // Grav 探针失败时回退门户投影，避免 soft-fail 环境全拦
                if (!secAuthGrantService.hasTableReadGrant(assetId)) {
                    throw new CommonException("无表读权限（Gravitino/门户）：" + qualify(schema, table));
                }
            }
        }
    }

    private List<GovAssetSourceLink> loadLinks(String dsId) {
        if (StrUtil.isBlank(dsId)) {
            return List.of();
        }
        return sourceLinkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getDsId, dsId.trim())
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE));
    }

    private String requireLinkedAssetId(String dsId, String schema, String table) {
        String assetId = resolveAssetId(loadLinks(dsId), schema, table);
        if (StrUtil.isBlank(assetId)) {
            throw new CommonException("表未入资产目录：" + qualify(schema, table) + "（请先在资产目录登记）");
        }
        return assetId;
    }

    /** 按 objectName 匹配 schema.table / 短名 / 末段。 */
    String resolveAssetId(List<GovAssetSourceLink> links, String schema, String table) {
        if (links == null || links.isEmpty() || StrUtil.isBlank(table)) {
            return null;
        }
        String t = table.trim();
        String s = StrUtil.trim(schema);
        for (GovAssetSourceLink link : links) {
            if (link == null || StrUtil.isBlank(link.getObjectName()) || StrUtil.isBlank(link.getAssetId())) {
                continue;
            }
            if (objectNameMatches(link.getObjectName().trim(), s, t)) {
                return link.getAssetId();
            }
        }
        return null;
    }

    static boolean objectNameMatches(String objectName, String schema, String table) {
        if (StrUtil.isBlank(objectName) || StrUtil.isBlank(table)) {
            return false;
        }
        String on = stripQuotes(objectName);
        String t = stripQuotes(table);
        String s = stripQuotes(schema);
        if (on.equalsIgnoreCase(t)) {
            return true;
        }
        if (StrUtil.isNotBlank(s) && on.equalsIgnoreCase(s + "." + t)) {
            return true;
        }
        String[] parts = on.split("\\.");
        if (parts.length >= 1 && parts[parts.length - 1].equalsIgnoreCase(t)) {
            if (StrUtil.isBlank(s) || parts.length == 1) {
                return true;
            }
            // schema.table 或 catalog.schema.table：校验倒数第二段
            return parts[parts.length - 2].equalsIgnoreCase(s);
        }
        return false;
    }

    static List<TableRef> extractTableRefs(String sql) {
        Set<String> seen = new LinkedHashSet<>();
        List<TableRef> out = new ArrayList<>();
        if (StrUtil.isBlank(sql)) {
            return out;
        }
        Matcher m = TABLE_REF.matcher(sql);
        while (m.find()) {
            String raw = m.group(1);
            if (StrUtil.isBlank(raw)) {
                continue;
            }
            // 跳过子查询开头
            if (raw.trim().startsWith("(")) {
                continue;
            }
            TableRef ref = parseQualified(raw);
            if (ref == null || StrUtil.isBlank(ref.table)) {
                continue;
            }
            // 常见非表关键字
            if (NON_TABLE_TOKENS.contains(ref.table.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String key = (StrUtil.blankToDefault(ref.schema, "") + "." + ref.table).toLowerCase(Locale.ROOT);
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
            return new TableRef("", parts[0]);
        }
        if (parts.length == 2) {
            return new TableRef(parts[0], parts[1]);
        }
        // catalog.schema.table → 末两段
        return new TableRef(parts[parts.length - 2], parts[parts.length - 1]);
    }

    static String stripSqlComments(String sql) {
        String s = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("(?m)--.*?$", " ");
        return s;
    }

    static String stripQuotes(String id) {
        if (id == null) {
            return "";
        }
        return id.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").trim();
    }

    static String qualify(String schema, String table) {
        if (StrUtil.isBlank(schema)) {
            return table;
        }
        return schema + "." + table;
    }

    static final class TableRef {
        final String schema;
        final String table;

        TableRef(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }
    }
}
