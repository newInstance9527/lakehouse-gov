package vip.xiaonuo.lh.modular.apply.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("apply_ticket_item")
public class ApplyTicketItem {

    @TableId
    private String id;
    private Integer revision;
    private String ticketId;
    private String assetId;
    private String gravAssetId;
    private String omFqn;
    private String action;
    private String detail;
    private String resultGrantId;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
