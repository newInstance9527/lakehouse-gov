package vip.xiaonuo.lh.modular.lifecycle.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@Schema(description = "生命周期运行 VO")
public class GovLcRunVo {

    private String runId;
    private String ws;
    private String kind;
    private String tableFqn;
    private String batchId;
    private String status;
    private String dsTaskId;
    private Boolean dryRun;
    private String metricsJson;
    private String errorMsg;
    private String operator;
    private String reqNo;
    private Integer retainLast;
    private Date startedAt;
    private Date finishedAt;
    private Date createTime;
}
