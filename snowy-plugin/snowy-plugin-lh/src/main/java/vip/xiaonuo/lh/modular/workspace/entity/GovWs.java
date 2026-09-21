package vip.xiaonuo.lh.modular.workspace.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 工作空间主数据 gov_ws
 */
@Getter
@Setter
@TableName("gov_ws")
@Schema(description = "工作空间")
public class GovWs extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String wsCode;
    private String name;
    private String icon;
    private String domainCode;
    private String costCenter;
    private String trinoRg;
    private String preferredSchemas;
    private String owners;
    private String detail;
    private String tagsJson;
    /** 脚本 Git 远程；空则门户本地仓库为 SoT */
    private String gitRemoteUrl;
}
