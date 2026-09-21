package vip.xiaonuo.lh.modular.lifecycle.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 孤儿扫描（gov_lc_orphan_scan）
 */
@Getter
@Setter
@TableName("gov_lc_orphan_scan")
@Schema(description = "孤儿扫描")
public class GovLcOrphanScan extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String bucket;
    private Long candidateCount;
    private Long bytes;
    private Boolean windowOk;
    private Boolean dryRun;
    private String resultUri;
    private String runId;
}
