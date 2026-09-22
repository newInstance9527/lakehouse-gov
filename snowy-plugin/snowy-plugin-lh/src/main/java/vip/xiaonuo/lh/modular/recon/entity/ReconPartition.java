package vip.xiaonuo.lh.modular.recon.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 湖/CK 分区对账流水（recon_partition）
 */
@Getter
@Setter
@TableName("recon_partition")
@Schema(description = "湖CK分区对账")
public class ReconPartition {

    @TableId
    private String id;
    private String metricCode;
    private String gravAssetId;
    private String lakeTable;
    private String ckDatabase;
    private String ckTable;
    private String partitionKey;
    private String lakeMetric;
    private String ckMetric;
    private BigDecimal diffRatio;
    private BigDecimal threshold;
    private String status;
    private Integer goldenFlag;
    private Date checkedAt;
    private String traceId;
    private Date createTime;
    private String createUser;
}
