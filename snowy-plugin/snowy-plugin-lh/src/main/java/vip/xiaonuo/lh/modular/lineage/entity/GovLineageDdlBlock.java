package vip.xiaonuo.lh.modular.lineage.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 血缘 DDL 阻断登记
 *
 * @author lakehouse
 * @date 2026/9/23
 */
@Getter
@Setter
@TableName("gov_lineage_ddl_block")
public class GovLineageDdlBlock extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String ws;
    private String tableName;
    private String fieldName;
    private String reason;
    private Integer active;
    private String evalId;
    private String ticketId;
}
