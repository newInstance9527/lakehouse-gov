package vip.xiaonuo.lh.modular.ai.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * AI 对话轮次（gov_ai_turn）
 */
@Getter
@Setter
@TableName("gov_ai_turn")
@Schema(description = "AI对话轮次")
public class GovAiTurn {

    @TableId
    private String id;
    private String sessionId;
    /** user|assistant|system */
    private String role;
    private String intent;
    private String content;
    private String citationsJson;
    private Integer promptTokens;
    private Integer completionTokens;
    private String modelId;
    private Integer latencyMs;
    private Date createTime;
    private String createUser;
}
