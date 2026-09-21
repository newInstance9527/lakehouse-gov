package vip.xiaonuo.lh.modular.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 法务冻结 / 法定保存期（gov_del_hold）：执行门闩，active 时禁止任何删除。
 */
@Getter
@Setter
@TableName("gov_del_hold")
@Schema(description = "合规删除法务冻结")
public class GovDelHold extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String reqId;
    private String subjectIdHash;
    private String scope;
    private String reason;
    private Date holdUntil;
    private String source;
    private Date releasedAt;
}
