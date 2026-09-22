package vip.xiaonuo.lh.modular.lineage.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从 OM 表级 lineage payload 提取列级边（columnsLineage），投影进门户字段边。
 *
 * @author lakehouse
 * @date 2026/9/23
 */
public final class OmColumnLineageExtractor {

    public static final String ETL_JOB_OM_COLUMN = "om_column";

    private OmColumnLineageExtractor() {
    }

    /**
     * @param data OM getTableLineage 的 data 对象（Map / JSONObject）
     * @return 去重后的列边：fromTable/fromField/toTable/toField/omFromFqn/omToFqn/transform
     */
    public static List<Map<String, String>> extractColumnEdges(Object data) {
        List<Map<String, String>> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        collectFromEdgeList(mapGet(data, "upstreamEdges"), out, seen);
        collectFromEdgeList(mapGet(data, "downstreamEdges"), out, seen);
        return out;
    }

    /**
     * 列 FQN 末段为字段，此前为表 FQN。
     *
     * @return [tableFqn, field] 或 null
     */
    public static String[] splitColumnFqn(String columnFqn) {
        if (StrUtil.isBlank(columnFqn)) {
            return null;
        }
        String fqn = columnFqn.trim();
        int i = fqn.lastIndexOf('.');
        if (i <= 0 || i >= fqn.length() - 1) {
            return null;
        }
        return new String[]{fqn.substring(0, i), fqn.substring(i + 1)};
    }

    /** 表展示名：取 FQN 末段；无点则原样。 */
    public static String shortTableName(String tableFqn) {
        if (StrUtil.isBlank(tableFqn)) {
            return "";
        }
        String t = tableFqn.trim();
        int i = t.lastIndexOf('.');
        return i >= 0 ? t.substring(i + 1) : t;
    }

    private static void collectFromEdgeList(Object edgesObj, List<Map<String, String>> out, Set<String> seen) {
        if (!(edgesObj instanceof List<?> list)) {
            return;
        }
        for (Object edge : list) {
            Object details = mapGet(edge, "lineageDetails");
            if (details == null) {
                continue;
            }
            Object cols = mapGet(details, "columnsLineage");
            if (!(cols instanceof List<?> colList)) {
                continue;
            }
            String sql = str(mapGet(details, "sqlQuery"));
            String functionHint = str(mapGet(details, "description"));
            for (Object col : colList) {
                String toCol = str(mapGet(col, "toColumn"));
                Object fromCols = mapGet(col, "fromColumns");
                String fn = firstNonBlank(str(mapGet(col, "function")), functionHint, sql);
                if (!(fromCols instanceof List<?> fromList) || StrUtil.isBlank(toCol)) {
                    continue;
                }
                String[] toParts = splitColumnFqn(toCol);
                if (toParts == null) {
                    continue;
                }
                for (Object fromObj : fromList) {
                    String fromCol = str(fromObj);
                    String[] fromParts = splitColumnFqn(fromCol);
                    if (fromParts == null) {
                        continue;
                    }
                    String key = (fromParts[0] + "|" + fromParts[1] + "|" + toParts[0] + "|" + toParts[1])
                            .toLowerCase(Locale.ROOT);
                    if (!seen.add(key)) {
                        continue;
                    }
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("fromTable", shortTableName(fromParts[0]));
                    row.put("fromField", fromParts[1]);
                    row.put("toTable", shortTableName(toParts[0]));
                    row.put("toField", toParts[1]);
                    row.put("omFromFqn", fromCol);
                    row.put("omToFqn", toCol);
                    row.put("transform", StrUtil.blankToDefault(fn, "om_column"));
                    out.add(row);
                }
            }
        }
    }

    private static Object mapGet(Object obj, String key) {
        if (obj instanceof Map<?, ?> m) {
            return m.get(key);
        }
        if (obj instanceof JSONObject jo) {
            return jo.get(key);
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
