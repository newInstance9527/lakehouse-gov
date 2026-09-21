package vip.xiaonuo.lh.modular.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 知识条目（gov_kb_entry）
 */
@Getter
@Setter
@TableName("gov_kb_entry")
@Schema(description = "知识条目")
public class GovKbEntry extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    /** indexing|ready|failed */
    private String status;
    private String ws;
    private String remark;
    /** term|dict|practice|faq|manual */
    private String cat;
    private String title;
    private String body;
    /** manual|upload */
    private String source;
    private String fileName;
    private String strategy;
    private Integer chunkSize;
    private Integer overlap;
    private String separator;
    private String embedModelId;
    private String refsJson;
    private Integer citeCnt;
}
