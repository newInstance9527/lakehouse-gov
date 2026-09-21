package vip.xiaonuo.lh.modular.ai.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * AI 动作审计（gov_ai_action_log）
 */
@Getter
@Setter
@TableName("gov_ai_action_log")
@Schema(description = "AI动作日志")
public class GovAiActionLog {

    @TableId
    private String id;
    private String sessionId;
    private String turnId;
    private String action;
    private String detailJson;
    private Date createTime;
    private String createUser;
}
