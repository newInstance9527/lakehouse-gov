package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

/**
 * 限制处理（个保法 §47 兜底）：删不掉的载体转「停止除存储与必要安全保护之外的处理」。
 */
@Getter
@Setter
public class GovDelRestrictParam {

    @NotBlank
    @Schema(description = "请求 id 或 req_no")
    private String reqId;

    @Schema(description = "计划项 id；为空表示整单剩余项")
    private List<String> targetIds;

    @NotBlank
    @Schema(description = "依据：法定保存期未届满 / 技术上难以实现 / 外部不可删")
    private String reason;

    @Schema(description = "复查日；缺省 90 天后")
    private Date reviewAt;
}
