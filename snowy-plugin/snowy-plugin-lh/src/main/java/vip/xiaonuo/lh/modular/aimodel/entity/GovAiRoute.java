package vip.xiaonuo.lh.modular.aimodel.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * AI 场景路由（gov_ai_route）
 */
@Getter
@Setter
@TableName("gov_ai_route")
@Schema(description = "AI路由")
public class GovAiRoute extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    /** sql / script / diagnose / manual / sandbox / embed */
    private String scene;
    /** * 或具体空间 */
    private String wsScope;
    private String primaryModelId;
    private String fallbackModelId;
    private Boolean enabled;
}
