package vip.xiaonuo.lh.modular.recon.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.ClickHouseClient;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.lifecycle.support.GovLcMetadataSql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 湖/CK 分区行数采集（对账用）：Iceberg 经 Trino COUNT，CK 经 HTTP COUNT。
 * 失败返回 -1（由 {@link ReconPartitionService#record} 记为 skipped），禁止伪造对齐。
 */
@Component
public class ReconEngineCounter {

    private static final Logger log = LoggerFactory.getLogger(ReconEngineCounter.class);
    private static final Pattern DT_EQ = Pattern.compile("(?i)dt\\s*=\\s*['\"]?(\\d{4}-\\d{2}-\\d{2})['\"]?");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    @Resource
    private TrinoClient trinoClient;
    @Resource
    private ClickHouseClient clickHouseClient;
    @Resource
    private LhProperties lhProperties;

    /**
     * @return map: lakeRows, ckRows, lakeDegraded, ckDegraded, partitionDt, lakeSql, ckSql
     */
    public Map<String, Object> countPartition(String lakeTable, String ckDatabase, String ckTable, String partitionKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        String dt = parsePartitionDt(partitionKey);
        out.put("partitionDt", dt);

        long lakeRows = -1L;
        boolean lakeDegraded = false;
        String lakeSql = null;
        if (StrUtil.isNotBlank(lakeTable)) {
            try {
                String catalog = icebergCatalog();
                GovLcMetadataSql.TableRef ref = GovLcMetadataSql.parse(normalizeLakeFqn(lakeTable, catalog), catalog);
                String where = StrUtil.isNotBlank(dt) ? " WHERE dt = DATE '" + dt + "'" : "";
                lakeSql = "SELECT count(*) AS cnt FROM "
                        + GovLcMetadataSql.quote(ref.schema()) + "." + GovLcMetadataSql.quote(ref.table())
                        + where;
                TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.job(5);
                opts.catalog = ref.catalog();
                opts.schema = ref.schema();
                opts.timeoutMs = 120_000;
                opts.source = "job.recon";
                opts.clientTags = "job.recon,partition-count";
                Map<String, Object> exec = trinoClient.execute(lakeSql, opts);
                if (Boolean.TRUE.equals(exec.get("degraded"))) {
                    lakeDegraded = true;
                } else {
                    lakeRows = parseCnt(exec);
                }
            } catch (Exception e) {
                lakeDegraded = true;
                log.warn("recon lake count soft-fail table={}: {}", lakeTable, e.getMessage());
            }
        } else {
            lakeDegraded = true;
        }

        long ckRows = -1L;
        boolean ckDegraded = false;
        String ckSql = null;
        if (!clickHouseClient.configured()) {
            ckDegraded = true;
        } else if (StrUtil.isNotBlank(ckTable) || StrUtil.isNotBlank(ckDatabase)) {
            try {
                String db = StrUtil.blankToDefault(ckDatabase, "");
                String tbl = StrUtil.blankToDefault(ckTable, "");
                if (StrUtil.isBlank(db) && tbl.contains(".")) {
                    int dot = tbl.lastIndexOf('.');
                    db = tbl.substring(0, dot);
                    tbl = tbl.substring(dot + 1);
                }
                if (StrUtil.isBlank(tbl) || !isIdent(db) || !isIdent(tbl)) {
                    throw new IllegalArgumentException("非法 CK 表: " + ckDatabase + "." + ckTable);
                }
                String where = StrUtil.isNotBlank(dt) ? " WHERE dt = toDate('" + dt + "')" : "";
                ckSql = "SELECT count(*) AS cnt FROM `" + db + "`.`" + tbl + "`" + where;
                Map<String, Object> exec = clickHouseClient.query(ckSql);
                if (Boolean.TRUE.equals(exec.get("degraded"))) {
                    ckDegraded = true;
                } else {
                    ckRows = parseCnt(exec);
                }
            } catch (Exception e) {
                ckDegraded = true;
                log.warn("recon ck count soft-fail {}.{}: {}", ckDatabase, ckTable, e.getMessage());
            }
        } else {
            ckDegraded = true;
        }

        out.put("lakeRows", lakeRows);
        out.put("ckRows", ckRows);
        out.put("lakeDegraded", lakeDegraded);
        out.put("ckDegraded", ckDegraded);
        out.put("lakeSql", lakeSql);
        out.put("ckSql", ckSql);
        return out;
    }

    static String parsePartitionDt(String partitionKey) {
        if (StrUtil.isBlank(partitionKey)) {
            return null;
        }
        String raw = partitionKey.trim();
        Matcher m = DT_EQ.matcher(raw);
        if (m.find()) {
            return m.group(1);
        }
        if (ISO_DATE.matcher(raw).matches()) {
            return raw;
        }
        return null;
    }

    private String normalizeLakeFqn(String lakeTable, String catalog) {
        String t = lakeTable.trim();
        if (t.toLowerCase(Locale.ROOT).startsWith("iceberg.")) {
            return t;
        }
        // ads.xxx → iceberg.ads.xxx
        if (t.chars().filter(ch -> ch == '.').count() == 1) {
            return catalog + "." + t;
        }
        return t;
    }

    private String icebergCatalog() {
        if (lhProperties.getGravitino() != null && StrUtil.isNotBlank(lhProperties.getGravitino().getCatalog())) {
            return lhProperties.getGravitino().getCatalog().trim();
        }
        return "iceberg";
    }

    static long parseCnt(Map<String, Object> exec) {
        Object rows = exec == null ? null : exec.get("rows");
        if (!(rows instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> map)) {
            return -1L;
        }
        Object cnt = map.get("cnt");
        if (cnt == null) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if ("cnt".equalsIgnoreCase(String.valueOf(e.getKey()))) {
                    cnt = e.getValue();
                    break;
                }
            }
        }
        if (cnt == null && map.size() == 1) {
            cnt = map.values().iterator().next();
        }
        if (cnt instanceof Number n) {
            return n.longValue();
        }
        if (cnt == null) {
            return -1L;
        }
        try {
            return Long.parseLong(String.valueOf(cnt).trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static boolean isIdent(String raw) {
        return StrUtil.isNotBlank(raw) && raw.matches("[A-Za-z_][A-Za-z0-9_]*");
    }
}
