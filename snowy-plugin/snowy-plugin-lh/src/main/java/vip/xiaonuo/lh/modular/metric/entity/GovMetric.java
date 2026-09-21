package vip.xiaonuo.lh.modular.metric.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 指标头（gov_metric）
 */
@Getter
@Setter
@TableName("gov_metric")
@Schema(description = "指标头")
public class GovMetric extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String metricCode;
    private String name;
    /** 原子 / 衍生 / 复合 */
    private String kind;
    private String domainCode;
    private String unit;
    private String owner;
    private String currentVer;
    private String currentVerId;
    private String omFqn;
    private String gravAssetId;
}
