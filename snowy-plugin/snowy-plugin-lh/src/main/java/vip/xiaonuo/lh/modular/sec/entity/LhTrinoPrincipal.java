package vip.xiaonuo.lh.modular.sec.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 门户用户 → Gravitino/Trino 主体。没有 privilege 列，不表示表授权。
 */
@Getter
@Setter
@TableName("lh_trino_principal")
public class LhTrinoPrincipal {

    @TableId
    private String id;
    private String portalUserId;
    private String portalAccount;
    private String trinoUser;
    /** 只允许 human */
    private String kind;
    private String status;
    private String remark;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
