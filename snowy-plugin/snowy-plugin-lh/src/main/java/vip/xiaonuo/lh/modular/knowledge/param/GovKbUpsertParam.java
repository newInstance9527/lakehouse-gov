package vip.xiaonuo.lh.modular.knowledge.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovKbUpsertParam {

    private String title;
    /** term|dict|practice|faq|manual|minutes */
    private String cat;
    private String body;
    /** manual|upload */
    private String source;
    private String fileName;
    private String strategy;
    private Integer chunkSize;
    private Integer overlap;
    private String separator;
    private String embedModelId;
    /** 关联文案或 JSON */
    private String refs;
    private String refsJson;
    private String ws;
    /** workspace | platform */
    private String scope;
    private String remark;
    /** 人工覆盖质量分 0-10 */
    private java.math.BigDecimal qualityScore;
    private String scoreReason;
    private String tagsJson;
    private String lifecycleStatus;
}
