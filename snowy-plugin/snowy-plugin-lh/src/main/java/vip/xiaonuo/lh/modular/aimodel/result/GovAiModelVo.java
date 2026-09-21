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
    /** 脱敏 Key */
    private String key;
    private String keyMask;
    private Integer contextTokens;
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
}
