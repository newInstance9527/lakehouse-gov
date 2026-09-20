package vip.xiaonuo.lh.modular.apply.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("apply_ticket")
public class ApplyTicket {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String ticketNo;
    private String ticketType;
    private String title;
    private String applicant;
    private String reason;
    private String payload;
    private String approvedBy;
    private Date approvedAt;
    private Date expiresAt;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
