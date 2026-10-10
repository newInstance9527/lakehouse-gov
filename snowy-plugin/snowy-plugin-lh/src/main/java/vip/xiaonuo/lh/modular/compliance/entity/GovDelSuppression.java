package vip.xiaonuo.lh.modular.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 抑制名单（gov_del_suppression）：防 CDC / 回算把已删主体带回。
 */
@Getter
@Setter
@TableName("gov_del_suppression")
@Schema(description = "合规抑制名单")
public class GovDelSuppression extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String reqId;
    private String subjectType;
    private String subjectIdHash;
    /** 表 FQN；{@code *} = 主体级 */
    private String objectFqn;
    private Date effectiveAt;
    private Date expiresAt;
    private String source;
}
