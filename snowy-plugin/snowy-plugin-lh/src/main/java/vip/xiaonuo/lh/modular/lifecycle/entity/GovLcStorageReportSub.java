package vip.xiaonuo.lh.modular.lifecycle.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("gov_lc_storage_report_sub")
public class GovLcStorageReportSub {

    @TableId
    private String id;
    private Integer revision;
    private String ws;
    private String rangeKey;
    private String format;
    private String webhookUrl;
    private String cronExpr;
    private Integer enabled;
    private String lastStatus;
    private String lastMessage;
    private Date lastRunTime;
    @TableField(fill = FieldFill.INSERT)
    private String createUser;
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
    @TableField(fill = FieldFill.UPDATE)
    private String updateUser;
    @TableField(fill = FieldFill.UPDATE)
    private Date updateTime;
    @TableLogic
    private String deleteFlag;
}
