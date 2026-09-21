package vip.xiaonuo.lh.modular.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 执行与状态流水（gov_del_exec，APPEND）：证据包的原始来源。
 */
@Getter
@Setter
@TableName("gov_del_exec")
@Schema(description = "合规删除执行流水")
public class GovDelExec extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String reqId;
    private String targetId;
    private String step;
    private String execKey;
    private String runId;
    private String operator;
    private String detail;
    private Date startedAt;
    private Date endedAt;
}
