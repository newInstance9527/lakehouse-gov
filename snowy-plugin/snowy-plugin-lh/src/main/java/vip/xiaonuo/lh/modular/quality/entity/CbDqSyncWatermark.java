package vip.xiaonuo.lh.modular.quality.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("cb_dq_sync_watermark")
public class CbDqSyncWatermark {
    @TableId
    private String id;
    private String sourceSystem;
    private String markKey;
    private String markValue;
    private Date updateTime;
    private String updateUser;
}
