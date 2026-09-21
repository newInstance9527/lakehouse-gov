package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 主体索引登记 / 维护。
 */
@Getter
@Setter
public class GovDelSubjectMapUpsertParam {

    @Schema(description = "已有记录 id；为空按 (ws, subjectType, objectFqn) upsert")
    private String id;

    @NotBlank
    @Schema(description = "主体类型")
    private String subjectType;

    @NotBlank
    @Schema(description = "载体")
    private String carrier;

    @NotBlank
    @Schema(description = "对象 FQN")
    private String objectFqn;

    @Schema(description = "主体 ID 所在列（代理键优先）")
    private String idColumn;

    @Schema(description = "无主体列时的关联路径")
    private String joinPath;

    @Schema(description = "执行方式")
    private String deleteMode;

    @Schema(description = "范围模板")
    private String scopeTpl;

    @Schema(description = "负责人")
    private String owner;

    @Schema(description = "敏感级：公开/内部/秘密/机密")
    private String sensitivity;

    @Schema(description = "状态 active/disabled")
    private String status;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "备注")
    private String remark;
}
