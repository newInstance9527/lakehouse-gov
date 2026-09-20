package vip.xiaonuo.lh.modular.etl.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * ETL 发布/解析水位
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Getter
@Setter
@TableName("cb_etl_deploy_watermark")
@Schema(description = "ETL 部署水位")
public class CbEtlDeployWatermark {

    @TableId
    private String id;
    private String sourceSystem;
    private String markKey;
    private String markValue;
    private Date updateTime;
    private String updateUser;
}
