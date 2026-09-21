package vip.xiaonuo.lh.modular.metric.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 指标物化登记（gov_metric_materialize，P2）
 */
@Getter
@Setter
@TableName("gov_metric_materialize")
public class GovMetricMaterialize extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String metricCode;
    private String ver;
    private String engine;
    private String targetTable;
    private String grainJson;
    private String jobRef;
    private Integer reconOk;
}
