package vip.xiaonuo.lh.modular.lifecycle.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 表存储投影（gov_lc_table_stat）
 */
@Getter
@Setter
@TableName("gov_lc_table_stat")
@Schema(description = "表存储投影")
public class GovLcTableStat extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String tableFqn;
    private String layer;
    private Long sizeBytes;
    private Long activeBytes;
    private Long reclaimableBytes;
    private Long fileCount;
    private Long avgFileBytes;
    private Long smallFileCount;
    private BigDecimal smallFileRatio;
    /** 库列名为 growth_7d_pct；默认驼峰会误映射成 growth7d_pct。 */
    @TableField("growth_7d_pct")
    private BigDecimal growth7dPct;
    private Integer snapshotCount;
    private String policyLabel;
    private String collectStatus;
    private String collectError;
    private Date collectedAt;
}
