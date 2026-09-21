package vip.xiaonuo.lh.modular.lifecycle.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 生命周期执行留痕（gov_lc_run）
 */
@Getter
@Setter
@TableName("gov_lc_run")
@Schema(description = "生命周期运行")
public class GovLcRun extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String reqNo;
    private Integer retainLast;
    private String kind;
    private String tableFqn;
    private String batchId;
    private String dsTaskId;
    private Boolean dryRun;
    private String metricsJson;
    private String errorMsg;
    private String operator;
    private Date startedAt;
    private Date finishedAt;
}
