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
     * 每项建议字段：assetId（优先）、schema、table（或 name / fqn）、columns[{name,type}]。
     * 仅作生成上下文；服务端按 owned ∪ hasTableReadGrant 软剥离未授权表，不旁路 ACL、不自动执行。
     */
    private java.util.List<java.util.Map<String, Object>> schemaContext;

    /**
     * 构建 API 脚本类型：SQL | GROOVY（scene=api_script / build_api 时生效）。
     * 仅影响生成形态与 apply 动作，不旁路 ACL。
     */
    private String scriptType;

    /** 已选数据源门户 id（仅上下文提示，不做授权旁路）。 */
    private String datasourceId;

    /** 数据源类型 / SQLREST type 提示（如 postgresql、mysql）。 */
    private String datasourceType;
}
