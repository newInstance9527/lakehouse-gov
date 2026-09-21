package vip.xiaonuo.lh.modular.lifecycle.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 存储趋势变更点（gov_lc_storage_change_point）
 */
@Getter
@Setter
@TableName("gov_lc_storage_change_point")
@Schema(description = "存储趋势变更点")
public class GovLcStorageChangePoint extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private Date dt;
    private String scopeType;
    private String scopeKey;
    private String kind;
    private String note;
    private String sourceRef;
}
