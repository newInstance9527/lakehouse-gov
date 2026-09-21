package vip.xiaonuo.lh.modular.lifecycle.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@Schema(description = "生命周期策略 VO")
public class GovLcPolicyVo {

    private String id;
    private String ws;
    private String status;
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
    private String remark;
    private Date updateTime;
}
