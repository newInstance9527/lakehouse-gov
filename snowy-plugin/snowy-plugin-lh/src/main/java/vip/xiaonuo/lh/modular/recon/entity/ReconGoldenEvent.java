package vip.xiaonuo.lh.modular.recon.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 黄金摘牌/重导留痕（recon_golden_event · §33.3）
 */
@Getter
@Setter
@TableName("recon_golden_event")
@Schema(description = "黄金摘牌事件")
public class ReconGoldenEvent {

    @TableId
    private String id;
    private String ws;
    private String lakeTable;
    private String metricCode;
    private String action;
    private String status;
    private String note;
    private String traceId;
    /** DS workflow / processInstance 引用 */
    private String jobRef;
    /** DS processInstanceId */
    private String dsInstanceId;
    private Date createTime;
    private String createUser;
}
