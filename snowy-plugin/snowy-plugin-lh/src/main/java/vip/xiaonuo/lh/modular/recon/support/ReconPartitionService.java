package vip.xiaonuo.lh.modular.recon.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricMaterialize;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMaterializeMapper;
import vip.xiaonuo.lh.modular.metric.support.MetricMaterializeRewrite;
import vip.xiaonuo.lh.modular.metric.support.MetricPartitionReconGate;
import vip.xiaonuo.lh.modular.recon.entity.ReconPartition;
import vip.xiaonuo.lh.modular.recon.mapper.ReconPartitionMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 湖/CK 分区对账：落 {@code recon_partition}，并回写 {@code gov_metric_materialize.recon_ok}。
 */
@Component
public class ReconPartitionService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.001");

    @Resource
    private ReconPartitionMapper reconPartitionMapper;
    @Resource
    private GovMetricMaterializeMapper materializeMapper;

    public Map<String, Object> listRecent(String metricCode, String status, int limit) {
        int n = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        var qw = new QueryWrapper<ReconPartition>().lambda()
                .orderByDesc(ReconPartition::getCheckedAt)
                .last("LIMIT " + n);
        if (StrUtil.isNotBlank(metricCode)) {
            qw.eq(ReconPartition::getMetricCode, metricCode.trim().toUpperCase(Locale.ROOT));
        }
        if (StrUtil.isNotBlank(status) && !"all".equalsIgnoreCase(status)) {
            qw.eq(ReconPartition::getStatus, status.trim().toLowerCase(Locale.ROOT));
        }
        List<ReconPartition> rows = reconPartitionMapper.selectList(qw);
        long fail = rows.stream().filter(r -> MetricPartitionReconGate.STATUS_FAIL.equalsIgnoreCase(r.getStatus())).count();
        long pass = rows.stream().filter(r -> MetricPartitionReconGate.STATUS_PASS.equalsIgnoreCase(r.getStatus())).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("passCount", pass);
        out.put("failCount", fail);
        out.put("records", rows.stream().map(this::toMap).toList());
        return out;
    }

    /**
     * 登记一次分区对账结果（可由 DS 回调或门户手动/种子触发）。
     */
    public Map<String, Object> record(Map<String, Object> param) {
        String metricCode = upper(str(param.get("metricCode")));
        String partitionKey = StrUtil.blankToDefault(str(param.get("partitionKey")),
                "dt=" + StrUtil.blankToDefault(str(param.get("partitionDt")), today()));
        long lakeRows = longVal(param.get("lakeRows"), nestedRows(param.get("lakeMetric")));
        long ckRows = longVal(param.get("ckRows"), nestedRows(param.get("ckMetric")));
        BigDecimal threshold = decimal(param.get("threshold"), DEFAULT_THRESHOLD);

        BigDecimal diffRatio = BigDecimal.ZERO;
        String status = MetricPartitionReconGate.STATUS_PASS;
        if (lakeRows < 0 || ckRows < 0) {
            status = "skipped";
        } else if (lakeRows == 0 && ckRows == 0) {
            diffRatio = BigDecimal.ZERO;
        } else {
            long base = Math.max(lakeRows, 1);
            diffRatio = BigDecimal.valueOf(Math.abs(lakeRows - ckRows))
                    .divide(BigDecimal.valueOf(base), 8, RoundingMode.HALF_UP);
            if (diffRatio.compareTo(threshold) > 0) {
                status = MetricPartitionReconGate.STATUS_FAIL;
            }
        }

        String lakeJson = metricJson(param.get("lakeMetric"), lakeRows, str(param.get("lakeChecksum")));
        String ckJson = metricJson(param.get("ckMetric"), ckRows, str(param.get("ckChecksum")));

        ReconPartition row = new ReconPartition();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setMetricCode(metricCode);
        row.setGravAssetId(str(param.get("gravAssetId")));
        row.setLakeTable(str(param.get("lakeTable")));
        String ckTable = str(param.get("ckTable"));
        String ckDb = str(param.get("ckDatabase"));
        if (StrUtil.isBlank(ckDb) && StrUtil.isNotBlank(ckTable) && ckTable.contains(".")) {
            int dot = ckTable.lastIndexOf('.');
            ckDb = ckTable.substring(0, dot);
            ckTable = ckTable.substring(dot + 1);
        }
        row.setCkDatabase(ckDb);
        row.setCkTable(ckTable);
        row.setPartitionKey(partitionKey);
        row.setLakeMetric(lakeJson);
        row.setCkMetric(ckJson);
        row.setDiffRatio(diffRatio);
        row.setThreshold(threshold);
        row.setStatus(status);
        row.setGoldenFlag(bool01(param.get("goldenFlag")));
        row.setCheckedAt(new Date());
        row.setTraceId(StrUtil.blankToDefault(str(param.get("traceId")), "trc-" + row.getId()));
        row.setCreateTime(new Date());
        reconPartitionMapper.insert(row);

        Map<String, Object> sync = syncMaterializeReconOk(metricCode, ckDb, ckTable, status);

        Map<String, Object> out = toMap(row);
        out.put("materializeSync", sync);
        out.put("boardReady", MetricPartitionReconGate.STATUS_PASS.equals(status));
        return out;
    }

    /**
     * 对指定指标物化登记跑一轮「登记式」对账（无引擎时用入参行数；缺省视为一致以保持种子可用）。
     */
    public Map<String, Object> runForMetric(String metricCode, Map<String, Object> param) {
        String code = upper(metricCode);
        if (StrUtil.isBlank(code)) {
            throw new IllegalArgumentException("metricCode 不能为空");
        }
        GovMetricMaterialize mat = materializeMapper.selectOne(new QueryWrapper<GovMetricMaterialize>().lambda()
                .eq(GovMetricMaterialize::getDeleteFlag, NOT_DELETE)
                .eq(GovMetricMaterialize::getMetricCode, code)
                .eq(GovMetricMaterialize::getEngine, "clickhouse")
                .eq(GovMetricMaterialize::getStatus, "active")
                .orderByDesc(GovMetricMaterialize::getUpdateTime)
                .last("LIMIT 1"));
        Map<String, Object> body = param == null ? new LinkedHashMap<>() : new LinkedHashMap<>(param);
        body.put("metricCode", code);
        if (mat != null) {
            body.putIfAbsent("ckTable", mat.getTargetTable());
            if (StrUtil.isBlank(str(body.get("lakeTable")))) {
                body.put("lakeTable", "iceberg." + mat.getTargetTable());
            }
        }
        if (body.get("lakeRows") == null && body.get("ckRows") == null && body.get("lakeMetric") == null) {
            // 无外部度量时默认对齐（联调）；显式传差异则 fail
            body.put("lakeRows", 1L);
            body.put("ckRows", 1L);
        }
        return record(body);
    }

    private Map<String, Object> syncMaterializeReconOk(String metricCode, String ckDb, String ckTable, String status) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("updated", 0);
        if (StrUtil.isBlank(metricCode) && StrUtil.isBlank(ckTable)) {
            return out;
        }
        int reconOk = MetricPartitionReconGate.STATUS_PASS.equals(status) ? 1 : 0;
        var qw = new QueryWrapper<GovMetricMaterialize>().lambda()
                .eq(GovMetricMaterialize::getDeleteFlag, NOT_DELETE)
                .eq(GovMetricMaterialize::getStatus, "active");
        if (StrUtil.isNotBlank(metricCode)) {
            qw.eq(GovMetricMaterialize::getMetricCode, metricCode);
        }
        List<GovMetricMaterialize> mats = materializeMapper.selectList(qw);
        int updated = 0;
        List<String> ids = new ArrayList<>();
        for (GovMetricMaterialize m : mats) {
            if (StrUtil.isNotBlank(ckTable) && StrUtil.isNotBlank(m.getTargetTable())) {
                String t = m.getTargetTable().trim();
                String bare = t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
                if (!bare.equalsIgnoreCase(ckTable) && !t.equalsIgnoreCase(
                        StrUtil.blankToDefault(ckDb, "") + "." + ckTable)
                        && !t.toLowerCase(Locale.ROOT).endsWith("." + ckTable.toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            m.setReconOk(reconOk);
            m.setRevision(m.getRevision() == null ? 1 : m.getRevision() + 1);
            materializeMapper.updateById(m);
            updated++;
            ids.add(m.getId());
        }
        out.put("updated", updated);
        out.put("ids", ids);
        out.put("reconOk", reconOk);
        return out;
    }

    private Map<String, Object> toMap(ReconPartition r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("metricCode", r.getMetricCode());
        m.put("lakeTable", r.getLakeTable());
        m.put("ckDatabase", r.getCkDatabase());
        m.put("ckTable", r.getCkTable());
        m.put("partitionKey", r.getPartitionKey());
        m.put("lakeMetric", parseJson(r.getLakeMetric()));
        m.put("ckMetric", parseJson(r.getCkMetric()));
        m.put("diffRatio", r.getDiffRatio());
        m.put("threshold", r.getThreshold());
        m.put("status", r.getStatus());
        m.put("pass", MetricPartitionReconGate.STATUS_PASS.equalsIgnoreCase(r.getStatus()));
        m.put("goldenFlag", r.getGoldenFlag() != null && r.getGoldenFlag() == 1);
        m.put("checkedAt", r.getCheckedAt());
        m.put("traceId", r.getTraceId());
        return m;
    }

    private static Object parseJson(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Map.of();
        }
        try {
            return JSONUtil.parse(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    private static String metricJson(Object raw, long rows, String checksum) {
        if (raw instanceof Map<?, ?> map) {
            return JSONUtil.toJsonStr(map);
        }
        if (raw instanceof String s && StrUtil.isNotBlank(s) && s.trim().startsWith("{")) {
            return s.trim();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (rows >= 0) {
            m.put("rows", rows);
        }
        if (StrUtil.isNotBlank(checksum)) {
            m.put("checksum", checksum);
        }
        return JSONUtil.toJsonStr(m);
    }

    private static long nestedRows(Object raw) {
        if (raw instanceof Map<?, ?> map && map.get("rows") != null) {
            return longVal(map.get("rows"), -1L);
        }
        if (raw instanceof String s && StrUtil.isNotBlank(s)) {
            try {
                Object rows = JSONUtil.parseObj(s).get("rows");
                return longVal(rows, -1L);
            } catch (Exception ignored) {
            }
        }
        return -1L;
    }

    private static long longVal(Object a, long fallback) {
        if (a == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(a).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static BigDecimal decimal(Object o, BigDecimal def) {
        if (o == null) {
            return def;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int bool01(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        String s = String.valueOf(o).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s) ? 1 : 0;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static String upper(String s) {
        return StrUtil.isBlank(s) ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String today() {
        return java.time.LocalDate.now().toString();
    }
}
