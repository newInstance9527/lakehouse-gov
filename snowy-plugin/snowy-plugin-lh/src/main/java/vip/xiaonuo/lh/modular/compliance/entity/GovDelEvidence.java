package vip.xiaonuo.lh.modular.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 证据条目（gov_del_evidence，APPEND）。
 */
@Getter
@Setter
@TableName("gov_del_evidence")
@Schema(description = "合规删除证据条目")
public class GovDelEvidence extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String reqId;
    private String kind;
    private String title;
    private String objectPath;
    private String sha256;
    private String content;
}
