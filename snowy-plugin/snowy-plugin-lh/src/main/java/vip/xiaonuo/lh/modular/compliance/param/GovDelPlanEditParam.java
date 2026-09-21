package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 计划编辑：手工补充载体 / 排除载体（排除必填理由 → 派生限制处理）。
 */
@Getter
@Setter
public class GovDelPlanEditParam {

    @NotBlank
    @Schema(description = "请求 id 或 req_no")
    private String reqId;

    @Schema(description = "新增载体")
    private List<TargetItem> add;

    @Schema(description = "排除的计划项 id")
    private List<String> excludeIds;

    @Schema(description = "排除理由（excludeIds 非空时必填）")
    private String excludeReason;

    @Getter
    @Setter
    public static class TargetItem {

        @Schema(description = "载体：iceberg/ck/sink/export/platform/ai/meta/log/backup/kafka/source")
        private String carrier;

        @Schema(description = "对象 FQN / 目标")
        private String objectFqn;

        @Schema(description = "分区或谓词范围")
        private String scopeExpr;

        @Schema(description = "执行方式")
        private String mode;

        @Schema(description = "预估命中行")
        private Long rowsEst;
    }
}
