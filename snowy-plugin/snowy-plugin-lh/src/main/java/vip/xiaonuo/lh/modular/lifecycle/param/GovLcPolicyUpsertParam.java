package vip.xiaonuo.lh.modular.lifecycle.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 策略保存（按表 upsert）
 */
@Getter
@Setter
public class GovLcPolicyUpsertParam {

    @NotBlank
    @Schema(description = "表 FQN")
    private String tableFqn;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "active/disabled")
    private String status;

    @Schema(description = "保留快照数")
    private Integer keepCount;

    @Schema(description = "保留天数")
    private Integer keepDays;

    @Schema(description = "最少快照数")
    private Integer minSnapshots;

    @Schema(description = "L1/L2/L3")
    private String compactLevel;

    @Schema(description = "目标文件 MB")
    private Integer targetFileMb;

    @Schema(description = "orphan older_than 天数")
    private Integer orphanOlderDays;

    @Schema(description = "相对 expire 安全窗小时")
    private Integer orphanSafetyHours;

    @Schema(description = "分区过期天数")
    private Integer partitionExpireDays;

    @Schema(description = "分层 ODS/DWD/…")
    private String layer;

    @Schema(description = "负责人")
    private String owner;

    @Schema(description = "备注")
    private String remark;
}
