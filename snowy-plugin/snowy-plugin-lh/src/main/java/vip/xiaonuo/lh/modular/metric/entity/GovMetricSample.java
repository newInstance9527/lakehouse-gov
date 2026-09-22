package vip.xiaonuo.lh.modular.metric.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 指标日波动采样（gov_metric_sample）
 */
@Getter
@Setter
@TableName("gov_metric_sample")
public class GovMetricSample {

    @TableId
    private String id;
    private String ws;
    private String metricCode;
    private String ver;
    private Date sampleDt;
    private BigDecimal metricValue;
    private BigDecimal prevValue;
    private BigDecimal changePct;
    private Integer anomaly;
    private BigDecimal thresholdPct;
    private String status;
    private String message;
    private String queryId;
    private Date createTime;
}
