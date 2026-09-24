package vip.xiaonuo.lh.modular.contract.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("gov_contract_schema_ver")
public class GovContractSchemaVer {
    @TableId
    private String id;
    private String schemaId;
    private String ws;
    private String version;
    private String compat;
    private String fieldsJson;
    private String diffSummary;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
}
