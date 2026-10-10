package vip.xiaonuo.lh.modular.metric.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("gov_metric_dim")
public class GovMetricDim {
    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String code;
    private String name;
    private String domainCode;
    private String columnName;
    private String grainRole;
    private String factTable;
    private String bizProcess;
    /** DIM 层表 FQN（成员权威） */
    private String dimTable;
    private String memberCodeCol;
    private String memberNameCol;
    private String parentCodeCol;
    /** unbound / bound */
    private String dimStatus;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
