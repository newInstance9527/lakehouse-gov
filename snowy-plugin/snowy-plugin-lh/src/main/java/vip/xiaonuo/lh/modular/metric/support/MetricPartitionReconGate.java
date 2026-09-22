package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.recon.entity.ReconPartition;
import vip.xiaonuo.lh.modular.recon.mapper.ReconPartitionMapper;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 热路径 / 核心看板：检查 {@code recon_partition} 最近失败分区。
 * <p>失败 → 不得 prefer=hot，看板标记「数据未就绪」。</p>
 */
@Component
public class MetricPartitionReconGate {

    public static final String STATUS_PASS = "pass";
    public static final String STATUS_FAIL = "fail";

    @Resource
    private ReconPartitionMapper reconPartitionMapper;

    /**
     * @return null=通过；非空=阻断原因
     */
    public String blockReasonForMetric(String metricCode, String ckTable, int lookbackHours) {
        if (StrUtil.isBlank(metricCode) && StrUtil.isBlank(ckTable)) {
            return null;
        }
        Date since = new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(Math.max(1, lookbackHours)));
        var qw = new QueryWrapper<ReconPartition>().lambda()
                .eq(ReconPartition::getStatus, STATUS_FAIL)
                .ge(ReconPartition::getCheckedAt, since)
                .orderByDesc(ReconPartition::getCheckedAt)
                .last("LIMIT 1");
        if (StrUtil.isNotBlank(metricCode)) {
            qw.eq(ReconPartition::getMetricCode, metricCode.trim().toUpperCase());
        } else if (StrUtil.isNotBlank(ckTable)) {
            String t = ckTable.trim();
            int dot = t.lastIndexOf('.');
            String db = dot > 0 ? t.substring(0, dot) : null;
            String tbl = dot > 0 ? t.substring(dot + 1) : t;
            if (StrUtil.isNotBlank(db)) {
                qw.eq(ReconPartition::getCkDatabase, db);
            }
            qw.eq(ReconPartition::getCkTable, tbl);
        }
        ReconPartition fail = reconPartitionMapper.selectOne(qw);
        if (fail == null) {
            return null;
        }
        return "分区对账失败 " + fail.getPartitionKey()
                + " status=fail"
                + (StrUtil.isNotBlank(fail.getTraceId()) ? " trace=" + fail.getTraceId() : "");
    }

    public boolean hasRecentFail(String metricCode, int lookbackHours) {
        return blockReasonForMetric(metricCode, null, lookbackHours) != null;
    }

    public static long metricRows(String metricJson) {
        if (StrUtil.isBlank(metricJson)) {
            return -1L;
        }
        try {
            Object rows = JSONUtil.parseObj(metricJson).get("rows");
            if (rows == null) {
                return -1L;
            }
            return Long.parseLong(String.valueOf(rows));
        } catch (Exception e) {
            return -1L;
        }
    }
}
