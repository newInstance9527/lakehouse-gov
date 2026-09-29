package vip.xiaonuo.lh.modular.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 业务数据域 SoT（gov_domain）
 */
@Getter
@Setter
@TableName("gov_domain")
@Schema(description = "业务数据域")
public class GovDomain extends CommonEntity {

    @TableId
    private String id;
    private String ws;
    private String domainCode;
    private String name;
    private String owner;
    private Integer sortNo;
    /** active | disabled */
    private String status;
    private String remark;
    /** 扩展（aliases 等） */
    private String extraJson;
}
