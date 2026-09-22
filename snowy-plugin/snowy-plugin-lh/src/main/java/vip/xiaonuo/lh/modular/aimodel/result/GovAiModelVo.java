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
}
