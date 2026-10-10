package vip.xiaonuo.lh.modular.bi.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * BI 指标数据集绑定（gov_bi_dataset）
 */
@Getter
@Setter
@TableName("gov_bi_dataset")
public class GovBiDataset extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String metricCode;
    private String ver;
    private String dsId;
    private Long supersetDatabaseId;
    private Long supersetDatasetId;
    private String datasetName;
    private String sqlText;
    private String filtersHint;
    private String syncState;
    private Date lastSyncAt;
    private String lastError;
}
