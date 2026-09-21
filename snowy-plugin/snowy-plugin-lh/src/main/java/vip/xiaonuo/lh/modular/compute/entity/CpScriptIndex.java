package vip.xiaonuo.lh.modular.compute.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("cp_script_index")
public class CpScriptIndex {

    @TableId
    private String id;
    private String ws;
    private String path;
    private String name;
    private String folder;
    private String engine;
    private String env;
    private String status;
    private String gitSha;
    private String authorName;
    private String lintJson;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
