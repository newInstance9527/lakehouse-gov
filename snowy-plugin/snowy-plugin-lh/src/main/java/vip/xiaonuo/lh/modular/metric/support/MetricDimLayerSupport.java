package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 数仓 DIM 层挂接：FQN / 列名白名单与成员查询 SQL 拼装。
 * 成员权威在湖仓 DIM 表（schema=dim），门户不维护码表副本。
 */
public final class MetricDimLayerSupport {

    private static final Pattern COL_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    /** iceberg|hive.dim.<table> */
    private static final Pattern DIM_FQN_RE = Pattern.compile(
            "^(iceberg|hive)\\.dim\\.[A-Za-z_][A-Za-z0-9_]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_MEMBER = Pattern.compile(
            "^[A-Za-z0-9_\\-.\\u4e00-\\u9fa5]{1,64}$");

    private MetricDimLayerSupport() {
    }

    public static void assertDimLayerTable(String fqn) {
        String t = StrUtil.trim(fqn);
        if (StrUtil.isBlank(t)) {
            throw new IllegalArgumentException("dim_table 不能为空");
        }
        if (!DIM_FQN_RE.matcher(t).matches()) {
            throw new IllegalArgumentException(
                    "dim_table 须为 iceberg|hive.dim.<table>（DIM 层），当前: " + t);
        }
    }

    public static void assertCol(String col, String label) {
        if (StrUtil.isBlank(col) || !COL_RE.matcher(col.trim()).matches()) {
            throw new IllegalArgumentException(label + " 非法列名");
        }
    }

    public static boolean isSafeMember(String v) {
        return StrUtil.isNotBlank(v) && SAFE_MEMBER.matcher(v.trim()).matches();
    }

    public static String quoteIdent(String ident) {
        return "\"" + ident.trim().replace("\"", "") + "\"";
    }

    public static String quoteFqn(String fqn) {
        String[] parts = fqn.trim().split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(quoteIdent(parts[i]));
        }
        return sb.toString();
    }

    public static String escapeLiteral(String v) {
        return v.replace("'", "''");
    }

    public static String membersSql(String dimTable, String codeCol, String nameCol,
                                    String parentCol, String q, int limit) {
        assertDimLayerTable(dimTable);
        assertCol(codeCol, "member_code_col");
        assertCol(nameCol, "member_name_col");
        if (StrUtil.isNotBlank(parentCol)) {
            assertCol(parentCol, "parent_code_col");
        }
        int lim = Math.max(1, Math.min(limit, 500));
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ").append(quoteIdent(codeCol)).append(" AS code, ")
                .append(quoteIdent(nameCol)).append(" AS name");
        if (StrUtil.isNotBlank(parentCol)) {
            sb.append(", ").append(quoteIdent(parentCol)).append(" AS parent_code");
        }
        sb.append(" FROM ").append(quoteFqn(dimTable));
        if (StrUtil.isNotBlank(q)) {
            String like = "%" + escapeLiteral(q.trim()) + "%";
            sb.append(" WHERE CAST(").append(quoteIdent(codeCol)).append(" AS varchar) LIKE '")
                    .append(like).append("' OR CAST(").append(quoteIdent(nameCol))
                    .append(" AS varchar) LIKE '").append(like).append("'");
        }
        sb.append(" LIMIT ").append(lim);
        return sb.toString();
    }

    public static String existsSql(String dimTable, String codeCol, String memberCode) {
        assertDimLayerTable(dimTable);
        assertCol(codeCol, "member_code_col");
        if (!isSafeMember(memberCode)) {
            throw new IllegalArgumentException("非法维度成员码");
        }
        return "SELECT 1 AS ok FROM " + quoteFqn(dimTable)
                + " WHERE CAST(" + quoteIdent(codeCol) + " AS varchar) = '"
                + escapeLiteral(memberCode.trim()) + "' LIMIT 1";
    }

    public static String normalizeDimStatus(String dimTable) {
        return StrUtil.isBlank(dimTable) ? "unbound" : "bound";
    }

    public static String defaultCodeCol(String columnName, String memberCodeCol) {
        if (StrUtil.isNotBlank(memberCodeCol)) {
            return memberCodeCol.trim();
        }
        return StrUtil.blankToDefault(columnName, "code").trim();
    }

    public static String defaultNameCol(String memberNameCol) {
        return StrUtil.blankToDefault(memberNameCol, "name").trim();
    }

    public static boolean isLakeFqn(String fqn) {
        if (StrUtil.isBlank(fqn)) {
            return false;
        }
        String om = fqn.trim().toLowerCase(Locale.ROOT);
        return om.startsWith("iceberg.") || om.startsWith("hive.");
    }
}
