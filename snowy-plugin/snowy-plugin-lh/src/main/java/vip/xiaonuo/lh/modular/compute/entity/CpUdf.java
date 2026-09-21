package vip.xiaonuo.lh.modular.compute.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("cp_udf")
public class CpUdf {

    @TableId
    private String id;
    private String name;
    private String description;
    private String engines;
    private String engineLabel;
    private String ver;
    private String usesText;
    private String snippet;
    private String status;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
