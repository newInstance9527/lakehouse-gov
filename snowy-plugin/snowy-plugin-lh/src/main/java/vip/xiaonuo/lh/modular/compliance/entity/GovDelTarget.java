package vip.xiaonuo.lh.modular.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 删除计划项（gov_del_target）：载体矩阵的一次实例化。
 */
@Getter
@Setter
@TableName("gov_del_target")
@Schema(description = "合规删除计划项")
public class GovDelTarget extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String reqId;
    private String carrier;
    private Integer carrierOrder;
    private String objectFqn;
    private String scopeExpr;
    private String mode;
    private Long rowsEst;
    private Long rowsVerified;
    private String engineRef;
    private String excludeReason;
    private Date reviewAt;
    private String sourceMapId;
    private Boolean manualAdded;
}
