package vip.xiaonuo.lh.modular.metric.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 指标状态流转（gov_metric_history）
 */
@Getter
@Setter
@TableName("gov_metric_history")
public class GovMetricHistory {

    @TableId
    private String id;
    private String metricId;
    private String status;
    private String label;
    private String note;
    private Date createTime;
    private String createUser;
}
