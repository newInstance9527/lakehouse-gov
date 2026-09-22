package vip.xiaonuo.lh.modular.sec.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 列级 mask 投影（sec_mask_policy_proj）：OM/安全标签 → Grav/Trino 策略摘要。
 */
@Getter
@Setter
@TableName("sec_mask_policy_proj")
public class SecMaskPolicyProj {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String gravAssetId;
    private String columnName;
    private String sensitivity;
    private String maskAlgo;
    private String omTagFqn;
    private String gravPolicyId;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
