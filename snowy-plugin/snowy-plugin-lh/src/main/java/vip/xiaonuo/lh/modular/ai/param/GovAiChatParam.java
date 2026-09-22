package vip.xiaonuo.lh.modular.ai.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovAiChatParam {

    private String sessionId;
    private String ws;
    private String text;
    /** 前端芯片 scene / chipId */
    private String scene;
    private String modelOverride;
    /**
     * 数据服务工作台 schema linking（可选）：左树已选表/列。
     * 每项建议字段：schema、table（或 name）、columns[{name,type}]。
     * 仅作生成上下文；不旁路 ACL、不自动执行。
     */
    private java.util.List<java.util.Map<String, Object>> schemaContext;
}
