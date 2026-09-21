package vip.xiaonuo.lh.modular.ai.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * AI 会话（gov_ai_session）
 */
@Getter
@Setter
@TableName("gov_ai_session")
@Schema(description = "AI会话")
public class GovAiSession extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String userId;
    private String title;
    private String modelOverride;
}
