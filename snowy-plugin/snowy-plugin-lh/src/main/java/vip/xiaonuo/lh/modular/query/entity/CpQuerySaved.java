package vip.xiaonuo.lh.modular.query.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("cp_query_saved")
@Schema(description = "即席保存脚本")
public class CpQuerySaved {

    @TableId
    private String id;
    private String ws;
    private String userId;
    private String userName;
    private String name;
    private String sqlText;
    private String sqlHash;
    private String sqlSummary;
    private String engine;
    private String status;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
