package vip.xiaonuo.lh.modular.bi.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * BI 看板（gov_bi_dashboard，Superset 元数据回写）
 */
@Getter
@Setter
@TableName("gov_bi_dashboard")
public class GovBiDashboard extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private Long supersetDashboardId;
    private String dashboardTitle;
    private String dashboardSlug;
    private String urlPath;
    /** Superset embedded dashboard uuid（Guest Token 资源 id） */
    private String embeddedUuid;
    private Integer published;
    private Integer pinned;
    private String metricCodes;
    private String filtersHint;
    private String syncState;
    private Date lastSyncAt;
    private String lastError;
}
