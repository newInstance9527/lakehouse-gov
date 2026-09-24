package vip.xiaonuo.lh.modular.workspace.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.math.BigDecimal;

/**
 * 工作空间配额快照 gov_ws_quota
 */
@Getter
@Setter
@TableName("gov_ws_quota")
@Schema(description = "工作空间配额")
public class GovWsQuota extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String wsCode;
    private BigDecimal storageQuotaTb;
    private BigDecimal storageUsedTb;
    private Integer cuQuota;
    private Integer cuUsed;
    private Integer trinoQuota;
    private Integer trinoUsed;
    private Integer apiQpsQuota;
    private Integer apiQpsUsed;
    /** AI Token 日上限；null/0 = 不限 */
    private Long aiTokenQuota;
    /** AI 成本日上限；null/0 = 不限 */
    private BigDecimal aiCostQuota;
}
