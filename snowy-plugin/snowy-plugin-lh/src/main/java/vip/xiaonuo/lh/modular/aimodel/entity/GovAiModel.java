package vip.xiaonuo.lh.modular.aimodel.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.math.BigDecimal;

/**
 * AI 模型登记（gov_ai_model）
 */
@Getter
@Setter
@TableName("gov_ai_model")
@Schema(description = "AI模型")
public class GovAiModel extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String name;
    private String vendor;
    /** chat | embed */
    private String kind;
    private String modelName;
    private String baseUrl;
    private String vaultPath;
    /** 上下文窗展示，如 128K（与库 varchar 一致） */
    private String contextTokens;
    private String priceUnit;
    private BigDecimal inputRate;
    private BigDecimal outputRate;
    private Boolean enabled;
    private Integer latencyMs;
    private String roleLabel;
    private String keyMask;
    /** 累计调用次数 */
    private Long callsTotal;
    /** 累计成本 */
    private BigDecimal costTotal;
}
