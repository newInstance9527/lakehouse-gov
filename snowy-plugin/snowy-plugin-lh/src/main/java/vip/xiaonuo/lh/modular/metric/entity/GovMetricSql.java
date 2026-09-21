package vip.xiaonuo.lh.modular.metric.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 指标编译缓存（gov_metric_sql）
 */
@Getter
@Setter
@TableName("gov_metric_sql")
public class GovMetricSql {

    @TableId
    private String id;
    private String verId;
    private String dialect;
    private String sqlText;
    private String depClosure;
    private String bindAssets;
    private Date createTime;
}
