package vip.xiaonuo.lh.modular.workspace.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 用户当前协作空间偏好 gov_ws_user_pref
 */
@Getter
@Setter
@TableName("gov_ws_user_pref")
@Schema(description = "用户工作空间偏好")
public class GovWsUserPref {

    @TableId
    private String id;
    private String userId;
    private String currentWsCode;
    private Date createTime;
    private Date updateTime;
}
