package vip.xiaonuo.lh.modular.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 每主体 PII 列 DEK 登记（{@code gov_del_subject_dek}）：删钥即 crypto-shredding。
 * <p>DEK 材料只在 Vault；本表仅 fingerprint / vault_path / 状态。</p>
 */
@Getter
@Setter
@TableName("gov_del_subject_dek")
@Schema(description = "合规删除主体 DEK 登记")
public class GovDelSubjectDek extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String subjectType;
    private String subjectIdHash;
    private String objectFqn;
    private String columnName;
    private String vaultPath;
    private String kekRef;
    private String dekFingerprint;
    private String shredReqId;
    private Date shreddedAt;
}
