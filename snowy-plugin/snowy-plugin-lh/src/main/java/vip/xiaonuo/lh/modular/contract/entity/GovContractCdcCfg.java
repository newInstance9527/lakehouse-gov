package vip.xiaonuo.lh.modular.contract.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("gov_contract_cdc_cfg")
public class GovContractCdcCfg {
    @TableId
    private String id;
    private String ws;
    private String topic;
    private String configJson;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
