package vip.xiaonuo.lh.modular.lifecycle.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 生命周期策略（gov_lc_policy）
 */
@Getter
@Setter
@TableName("gov_lc_policy")
@Schema(description = "生命周期策略")
public class GovLcPolicy extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String tableFqn;
    private Integer keepCount;
    private Integer keepDays;
    private Integer minSnapshots;
    private String compactLevel;
    private Integer targetFileMb;
    private Integer orphanOlderDays;
    private Integer orphanSafetyHours;
    private Integer partitionExpireDays;
    private String layer;
    private String owner;
}
