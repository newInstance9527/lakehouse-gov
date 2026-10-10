package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 抑制名单登记 / 更新。须提供 {@code subjectIdHash} 或 {@code reqId}（从请求取 hash）。
 */
@Getter
@Setter
public class GovDelSuppressionUpsertParam {

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "关联合规请求 id 或 req_no（可反查 subject_id_hash）")
    private String reqId;

    @Schema(description = "主体 HMAC；与 reqId 二选一")
    private String subjectIdHash;

    @Schema(description = "主体类型，默认 user")
    private String subjectType;

    @Schema(description = "表 FQN；空或 * = 主体级")
    private String objectFqn;

    @Schema(description = "生效时间；默认 now")
    private Date effectiveAt;

    @Schema(description = "失效时间；空=长期")
    private Date expiresAt;

    @Schema(description = "来源：manual / execute / restrict")
    private String source;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "status：active / released")
    private String status;
}
