package vip.xiaonuo.lh.modular.aimodel.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

@Getter
@Setter
public class GovAiModelUpsertParam {

    @Schema(description = "厂商")
    private String vendor;

    @Schema(description = "展示名")
    private String name;

    @Schema(description = "上游模型名（兼容字段 model）")
    private String model;

    @Schema(description = "上游模型名")
    private String modelName;

    @Schema(description = "OpenAI 兼容 base URL")
    private String baseUrl;

    @Schema(description = "API Key（仅创建/轮换提交；写入 Vault 后丢弃）")
    private String key;

    @Schema(description = "Key 过期时间（可选；巡检预警）")
    private Date keyExpiresAt;

    @Schema(description = "上下文窗展示（如 128K）")
    private String context;

    @Schema(description = "上下文窗展示（如 128K）")
    private String contextTokens;

    @Schema(description = "价格单位 usd_1m/cny_1k/free")
    private String priceUnit;

    private BigDecimal inputRate;
    private BigDecimal outputRate;

    @Schema(description = "角色备注")
    private String use;

    @Schema(description = "角色备注")
    private String role;

    @Schema(description = "角色备注")
    private String roleLabel;

    @Schema(description = "chat|embed")
    private String kind;

    @Schema(description = "local|egress；空则按 baseUrl/vendor 推断")
    private String egressKind;

    @Schema(description = "安全岗已标记外发可用（egress 模型必填为 true 方可进生产路由）")
    private Boolean egressApproved;

    @Schema(description = "工作空间")
    private String ws;

    private String remark;
}
