package vip.xiaonuo.lh.modular.metric.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 指标依赖边（gov_metric_dep）
 */
@Getter
@Setter
@TableName("gov_metric_dep")
public class GovMetricDep {

    @TableId
    private String id;
    private String verId;
    private String depCode;
    private String depVer;
    private Date createTime;
}
