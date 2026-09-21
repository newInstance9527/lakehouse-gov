package vip.xiaonuo.lh.modular.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 知识引用日统计（gov_kb_cite_stat）
 */
@Getter
@Setter
@TableName("gov_kb_cite_stat")
@Schema(description = "知识引用统计")
public class GovKbCiteStat {

    @TableId
    private String id;
    private String entryId;
    private Date day;
    private Integer citeCnt;
}
