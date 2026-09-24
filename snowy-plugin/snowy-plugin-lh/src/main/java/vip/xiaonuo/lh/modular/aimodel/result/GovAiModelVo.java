package vip.xiaonuo.lh.modular.aimodel.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

@Getter
@Setter
@Schema(description = "AI模型VO")
public class GovAiModelVo {

    private String id;
    private String name;
    private String vendor;
    private String kind;
    /** 对话模型是否支持图片输入（视觉/多模态） */
    private Boolean supportsVision;
    /** 是否支持图片输出（生图） */
    private Boolean supportsImageOutput;
    private String modelName;
    private String baseUrl;
    private String endpoint;
    /** 脱敏 Key（禁止明文） */
    private String key;
    private String keyMask;
    /** Key 过期时间 */
    private Date keyExpiresAt;
    /** Vault 路径指针（无密文） */
    private String vaultPath;
    /** 与库一致，如 128K */
    private String contextTokens;
    private String context;
    private String priceUnit;
    private BigDecimal inputRate;
    private BigDecimal outputRate;
    private Boolean enabled;
    /** local | egress */
    private String egressKind;
    /** 安全岗已评估外发可用 */
    private Boolean egressApproved;
    private String status;
    private Integer latencyMs;
    private String latency;
    private String roleLabel;
    private String role;
    private String ws;
    private Integer revision;
    private Date updateTime;
    /** LiteLLM 别名 lh/{id}（D3） */
    private String litellmAlias;
    /** 最近一次同步结果摘要（写操作后填充；列表可空） */
    private Boolean litellmSyncOk;
    private Boolean litellmSyncSkipped;
    private String litellmSyncMessage;
    /** 模型 Token 总限额；null/0 = 不限 */
    private Long tokenQuota;
    /** 模型成本总限额；null/0 = 不限 */
    private BigDecimal costQuota;
    /** 累计已用 Token（gov_ai_usage_daily 全量按 modelId 汇总） */
    private Long tokenUsed;
    private Long tokenRemaining;
    private Integer tokenPct;
    /** 累计已用成本 */
    private BigDecimal costUsed;
    private BigDecimal costRemaining;
    private Integer costPct;
    /** true = Token/成本至少一项有总限额 */
    private Boolean quotaLimited;
}
