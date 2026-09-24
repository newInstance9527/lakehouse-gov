package vip.xiaonuo.lh.modular.contract.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("gov_contract_change")
public class GovContractChange {
    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String schemaId;
    private String schemaName;
    private String title;
    private String changeSummary;
    private String compatResult;
    private String impactJson;
    private String fieldsJson;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
