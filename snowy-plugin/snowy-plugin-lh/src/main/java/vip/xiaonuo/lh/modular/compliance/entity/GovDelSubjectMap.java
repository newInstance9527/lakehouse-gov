package vip.xiaonuo.lh.modular.compliance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 主体索引（gov_del_subject_map）：主体在哪些载体、经什么列可定位。
 */
@Getter
@Setter
@TableName("gov_del_subject_map")
@Schema(description = "合规删除主体索引")
public class GovDelSubjectMap extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String subjectType;
    private String carrier;
    private String objectFqn;
    private String idColumn;
    private String joinPath;
    private String deleteMode;
    private String scopeTpl;
    private String owner;
    private String sensitivity;
    private Date verifiedAt;
}
