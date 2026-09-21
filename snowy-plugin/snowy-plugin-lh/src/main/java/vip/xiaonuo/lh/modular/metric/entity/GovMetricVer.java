package vip.xiaonuo.lh.modular.metric.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 指标版本（gov_metric_ver）
 */
@Getter
@Setter
@TableName("gov_metric_ver")
@Schema(description = "指标版本")
public class GovMetricVer extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String metricId;
    private String ver;
    private String caliber;
    private String formula;
    private String atomRef;
    private String agg;
    private String bindTable;
    private String bindField;
    private String qualifierJson;
    private String grainJson;
    private String timeWindow;
    private String fingerprint;
    private String pendingCaliber;
}
