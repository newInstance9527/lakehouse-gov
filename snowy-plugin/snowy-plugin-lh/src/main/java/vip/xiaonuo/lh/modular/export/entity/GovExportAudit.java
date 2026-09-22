package vip.xiaonuo.lh.modular.export.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 出湖出库审计（gov_export_audit）
 */
@Getter
@Setter
@TableName("gov_export_audit")
@Schema(description = "出湖出库审计")
public class GovExportAudit {

    @TableId
    private String id;
    private String ws;
    private String ticketId;
    private String ticketNo;
    private String eventType;
    private String srcTable;
    private String target;
    private String purpose;
    private String approver;
    private String dagId;
    private String dagCode;
    private String nodeKey;
    /** 1=Gravitino 属性标记成功；0=未写或 soft-fail */
    private Integer gravOk;
    private String gravRef;
    private String gravMessage;
    private String detailJson;
    private Date eventTime;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
}
