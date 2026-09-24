package vip.xiaonuo.lh.modular.contract.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("gov_contract_schema")
public class GovContractSchema {
    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String name;
    private String kind;
    private String schemaType;
    private String compat;
    private String fieldsJson;
    private Integer fieldCount;
    private String currentVersion;
    private String lastChange;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
