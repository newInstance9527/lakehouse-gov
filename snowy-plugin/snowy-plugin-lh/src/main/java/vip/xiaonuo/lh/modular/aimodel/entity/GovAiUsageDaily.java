package vip.xiaonuo.lh.modular.aimodel.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

/**
 * AI 日用量聚合（gov_ai_usage_daily）
 */
@Getter
@Setter
@TableName("gov_ai_usage_daily")
@Schema(description = "AI日用量")
public class GovAiUsageDaily {

    @TableId
    private String id;
    private Date day;
    private String ws;
    private String modelId;
    private Long calls;
    private Long promptTokens;
    private Long completionTokens;
    private BigDecimal costAmount;
    private String currency;
}
