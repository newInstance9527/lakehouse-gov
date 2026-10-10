package vip.xiaonuo.lh.modular.metric.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("gov_metric_qualifier")
public class GovMetricQualifier {
    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String code;
    private String name;
    private String domainCode;
    private String tablePattern;
    private String exprDsl;
    private String exprSql;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
