package vip.xiaonuo.lh.modular.ai.support;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.Map;

/**
 * AI SQL 草案：仅允许已 ACL 的 schemaContext / myAssets，禁止演示表与自由表名。
 */
public final class AiSqlDraftHelper {

    private AiSqlDraftHelper() {
    }

    /**
     * @return 只读 SELECT 草案；两端皆空时 null
     */
    public static String draftSelect(List<Map<String, Object>> schemaContext,
                                     List<Map<String, Object>> myAssets) {
        String fromSchema = fromSchema(schemaContext);
        if (StrUtil.isNotBlank(fromSchema)) {
            return fromSchema;
        }
        return fromMyAssets(myAssets);
    }

    public static String fromMyAssets(List<Map<String, Object>> myAssets) {
        if (myAssets == null || myAssets.isEmpty()) {
            return null;
        }
        Map<String, Object> first = myAssets.get(0);
        String code = String.valueOf(first.getOrDefault("assetCode", "")).trim();
        if (StrUtil.isBlank(code) || "null".equalsIgnoreCase(code)) {
            code = String.valueOf(first.getOrDefault("name", "")).trim();
        }
        if (StrUtil.isBlank(code) || "null".equalsIgnoreCase(code)) {
            return null;
        }
        String fqn = code.contains(".") ? code : "iceberg.default." + code;
        return "SELECT *\nFROM " + fqn + "\nWHERE 1 = 1\nLIMIT 100";
    }

    public static String fromSchema(List<Map<String, Object>> schemaContext) {
        if (schemaContext == null || schemaContext.isEmpty()) {
            return null;
        }
        Map<String, Object> first = schemaContext.get(0);
        String schema = String.valueOf(first.getOrDefault("schema", "")).trim();
        String table = String.valueOf(first.getOrDefault("table",
                first.getOrDefault("name", ""))).trim();
        if (StrUtil.isBlank(table) || "null".equalsIgnoreCase(table)) {
            table = String.valueOf(first.getOrDefault("assetCode", "")).trim();
        }
        if (StrUtil.isBlank(table) || "null".equalsIgnoreCase(table)) {
            return null;
        }
        String selectList = "*";
        Object colsObj = first.get("columns");
        if (colsObj instanceof List<?> cols && !cols.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Object c : cols) {
                String n = null;
                if (c instanceof Map<?, ?> m) {
                    Object raw = m.get("name");
                    if (raw != null && StrUtil.isNotBlank(String.valueOf(raw))) {
                        n = String.valueOf(raw).trim();
                    }
                } else if (c != null && StrUtil.isNotBlank(String.valueOf(c))) {
                    n = String.valueOf(c).trim();
                }
                if (n == null) {
                    continue;
                }
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(n);
            }
            if (names.length() > 0) {
                selectList = names.toString();
            }
        }
        String fqn;
        if (table.contains(".")) {
            fqn = table;
        } else if (StrUtil.isNotBlank(schema) && !"null".equalsIgnoreCase(schema)) {
            fqn = schema + "." + table;
        } else {
            fqn = "iceberg.default." + table;
        }
        return "SELECT " + selectList + "\nFROM " + fqn + "\nLIMIT 100";
    }
}
