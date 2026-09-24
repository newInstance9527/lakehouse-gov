package vip.xiaonuo.lh.modular.observability.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("gov_obs_span")
public class GovObsSpan {
    @TableId
    private String id;
    private String ws;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String linkId;
    private String service;
    private String op;
    private String status;
    private Date startTs;
    private Date endTs;
    private Long durationMs;
    private String runId;
    private String eventId;
    private String error;
    private String attrsJson;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
}
