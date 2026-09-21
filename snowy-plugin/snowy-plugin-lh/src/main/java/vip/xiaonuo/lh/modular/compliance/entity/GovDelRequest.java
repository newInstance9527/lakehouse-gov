package vip.xiaonuo.lh.modular.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 合规删除请求（gov_del_request）。
 * <p>
 * 主体 ID 明文禁止落库：仅 {@code subjectIdHash}（HMAC）+ {@code vaultPath}。
 */
@Getter
@Setter
@TableName("gov_del_request")
@Schema(description = "合规删除请求")
public class GovDelRequest extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String reqNo;
    private String subjectType;
    private String subjectIdHash;
    private String subjectMasked;
    private String vaultPath;
    private String reqType;
    private String legalBasis;
    private String scopeLabel;
    private String sourceSystem;
    private String sourceRef;
    private String ticketNo;
    private Date deadline;
    private Date execWindow;
    private Date executedAt;
    private Date verifiedAt;
    private Date destroyAfter;
    private String holdReason;
    private String applicant;
}
