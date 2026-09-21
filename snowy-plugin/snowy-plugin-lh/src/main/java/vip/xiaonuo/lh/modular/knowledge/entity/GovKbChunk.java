package vip.xiaonuo.lh.modular.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 知识分片（gov_kb_chunk）
 */
@Getter
@Setter
@TableName("gov_kb_chunk")
@Schema(description = "知识分片")
public class GovKbChunk {

    @TableId
    private String id;
    private String entryId;
    private Integer ordinal;
    private String textContent;
    private Integer tokenEst;
    private Date createTime;
}
